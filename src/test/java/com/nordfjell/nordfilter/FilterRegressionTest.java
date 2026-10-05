package com.nordfjell.nordfilter;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class FilterRegressionTest {
    private static int passed;
    private static final UUID A=new UUID(0,1),B=new UUID(0,2);
    private static Path file()throws IOException{return Files.createTempDirectory("nordfilter-unit-").resolve("data.yml");}
    private static void pass(String name){passed++;System.out.println("PASS "+name);}
    private static PunishmentStore.PlayerState mute(long until){return new PunishmentStore.PlayerState(1,until,1);}
    private static void rejected(String yaml)throws Exception{
        Path f=file();Files.writeString(f,yaml);byte[] before=Files.readAllBytes(f);
        try{new PunishmentStore(f);throw new AssertionError("Bad state accepted");}catch(IOException expected){}
        assert Arrays.equals(before,Files.readAllBytes(f));
    }
    private static FilterSettings config(String extra,String words)throws Exception{
        Path directory=Files.createTempDirectory("nordfilter-settings-");
        Files.writeString(directory.resolve("config.yml"),extra);
        Files.writeString(directory.resolve("banwords.yml"),words);
        return FilterSettings.load(directory);
    }
    private static FilterSettings defaults()throws Exception{return config("{}\n","words: [bad, evilword]\n");}
    private static FilterEngine engine()throws Exception{return new FilterEngine(new PunishmentStore(file()),defaults());}
    public static void main(String[] args)throws Exception{
        Path f=file();PunishmentStore store=new PunishmentStore(f);
        assert store.get(A).equals(PunishmentStore.EMPTY)&&store.recordCount()==0;
        pass("Reads do not create clean player records");
        assert store.put(A,mute(100000));assert store.get(A).muteUntil()==100000;
        assert store.flush(1,false);assert store.pendingCount()==0;
        assert new PunishmentStore(f).get(A).equals(mute(100000));
        pass("Pending mute restricts immediately; atomic legacy-format round trip");
        store.put(A,mute(100000));assert !store.flush(2,false);
        pass("Identical replay causes no write");
        assert store.put(A,store.desired(A).withoutMute());assert store.get(A).muteUntil()==100000;
        store.flush(3,false);assert store.get(A).muteUntil()==0;
        pass("Unmute cannot lift a committed mute before persistence");
        store.put(A,mute(200000));store.flush(4,false);store.reset(A);
        assert store.get(A).muteUntil()==200000;store.flush(5,false);assert store.get(A).equals(PunishmentStore.EMPTY);
        pass("Reset only clears committed punishment after persistence");

        PunishmentStore pending=new PunishmentStore(file());
        pending.put(A,mute(300000));pending.put(A,pending.desired(A).withoutMute());
        assert pending.get(A).muteUntil()==300000;pending.flush(1,false);assert pending.get(A).muteUntil()==0;
        pending.put(A,mute(400000));pending.reset(A);
        assert pending.get(A).muteUntil()==400000;pending.flush(2,false);assert pending.get(A).equals(PunishmentStore.EMPTY);
        pass("Unmute/reset also retain a mute that has not yet reached disk");

        Path failing=file();Files.writeString(failing,"players: {}\n");byte[] before=Files.readAllBytes(failing);
        AtomicBoolean fail=new AtomicBoolean();AtomicInteger attempts=new AtomicInteger();
        PunishmentStore failure=new PunishmentStore(failing,(path,bytes)->{
            attempts.incrementAndGet();if(fail.get())throw new IOException("Synthetic failure");Files.write(path,bytes);
        });
        failure.put(A,mute(500000));failure.flush(1,false);byte[] committed=Files.readAllBytes(failing);
        failure.put(A,failure.desired(A).withoutMute());fail.set(true);
        assert !failure.flush(2,false)&&failure.get(A).muteUntil()==500000;
        assert Arrays.equals(committed,Files.readAllBytes(failing));assert !failure.available();
        int calls=attempts.get();assert !failure.flush(3,false)&&attempts.get()==calls;
        fail.set(false);assert failure.flush(5_000_000_003L,false)&&failure.available();
        assert failure.get(A).muteUntil()==0&&failure.pendingCount()==0;
        pass("Failed release keeps old file/mute; bounded retry recovers");

        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        PunishmentStore slow=new PunishmentStore(file(),(path,bytes)->{
            entered.countDown();
            try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("Test timeout");}
            catch(InterruptedException e){throw new IOException(e);}
        });
        slow.put(A,mute(600000));
        Thread writer=new Thread(()->slow.flush(1,false));writer.start();assert entered.await(3,TimeUnit.SECONDS);
        long begin=System.nanoTime();slow.put(A,slow.desired(A).withoutMute());
        assert slow.get(A).muteUntil()==600000&&System.nanoTime()-begin<1_000_000_000L;
        release.countDown();writer.join(4000);assert !writer.isAlive();
        assert slow.pendingCount()==1&&slow.get(A).muteUntil()==600000;
        slow.flush(2,false);assert slow.get(A).muteUntil()==0;
        pass("Slow disk does not lock admission; newer release is not acknowledged early");

        AtomicInteger writes=new AtomicInteger();PunishmentStore burst=new PunishmentStore(file(),(path,bytes)->writes.incrementAndGet());
        for(int i=0;i<1000;i++)assert burst.put(new UUID(1,i),mute(700000));
        burst.flush(1,false);assert writes.get()==1&&burst.recordCount()==1000;
        for(int i=0;i<1000;i++)burst.put(new UUID(1,i),mute(700000));
        assert !burst.flush(2,false);pass("1000 updates coalesce to one transaction; replay writes zero");
        PunishmentStore bounded=new PunishmentStore(file(),(path,bytes)->{});
        for(int i=0;i<PunishmentStore.MAX_PENDING;i++)assert bounded.put(new UUID(2,i),mute(1));
        assert !bounded.put(A,mute(1))&&!bounded.available()&&bounded.pendingCount()==PunishmentStore.MAX_PENDING;
        bounded.flush(1,false);assert !bounded.available();pass("Pending capacity bounded, overflow latched");

        rejected("players:\n  broken:\n    level: 1\n");pass("Bad UUID rejected without overwriting");
        rejected("players: null\n");pass("Null state cannot silently reset mutes");
        rejected("null\n");rejected("~\n");rejected("---\nnull\n");
        Path empty=file();Files.writeString(empty,"  \n# legacy empty file\n");assert new PunishmentStore(empty).recordCount()==0;
        pass("Explicit root null rejected; genuinely blank/comment-only legacy state supported");
        rejected("players:\n  "+A+":\n    mute-until: -1\n");pass("Negative timestamp rejected");
        rejected("players:\n  "+A+":\n    level: '1'\n");pass("Wrong integer type rejected");
        rejected("players:\n  "+A+":\n    level: 1\n    level: 0\n");pass("Duplicate YAML fields rejected");
        rejected("players: !!java.util.HashMap {}\n");pass("Unsafe YAML tag rejected");
        rejected("players:\n  "+A+":\n    unknown: 1\n");pass("Unknown punishment field rejected");
        Path utf=file();Files.write(utf,new byte[]{(byte)0xc3,0x28});
        try{new PunishmentStore(utf);throw new AssertionError();}catch(IOException expected){}
        pass("Invalid UTF-8 rejected");
        Path large=file();Files.write(large,new byte[SafeYaml.MAX_BYTES+1]);
        try{new PunishmentStore(large);throw new AssertionError();}catch(IOException expected){}
        pass("Size bounded before parsing");

        WordMatcher matcher=new WordMatcher(List.of("he","she","hers","his","bad"));
        assert matcher.matches("ushers")&&matcher.matches("xhis")&&!matcher.matches("clean");
        assert matcher.matches(FilterEngine.normalize("B.á.D"));
        pass("Automaton handles suffix overlap, accents and punctuation");
        Random random=new Random(4);List<String> patterns=new ArrayList<>();
        for(int i=0;i<200;i++){StringBuilder text=new StringBuilder();for(int j=0;j<1+random.nextInt(6);j++)text.append((char)('a'+random.nextInt(6)));patterns.add(text.toString());}
        matcher=new WordMatcher(patterns);
        for(int i=0;i<1000;i++){
            StringBuilder text=new StringBuilder();for(int j=0;j<40;j++)text.append((char)('a'+random.nextInt(8)));
            String value=text.toString();assert matcher.matches(value)==patterns.stream().anyMatch(value::contains);
        }
        pass("1000 randomized automaton comparisons match substring oracle");

        FilterEngine policy=engine();long wall=100000,mono=1_000_000;
        assert policy.inspect(A,false,"alpha",wall,mono).allowed();
        assert policy.inspect(A,false,"beta",wall,mono+1).allowed();
        assert policy.inspect(A,false,"alpha",wall,mono+2).allowed();
        assert policy.inspect(A,false,"beta",wall,mono+3).allowed();
        assert !policy.inspect(A,false,"alpha",wall,mono+4).allowed();
        pass("Alternating messages no longer reset repeated-message history");
        policy=engine();assert policy.inspect(A,false,"same",wall,mono).allowed();
        assert policy.inspect(A,false,"same",wall,mono+31_000_000_000L).allowed();
        assert policy.inspect(A,false,"same",wall-1000,mono+31_000_000_001L).allowed();
        assert !policy.inspect(A,false,"same",wall-1000,mono+31_000_000_002L).allowed();
        pass("Window expiry uses monotonic time despite wall-clock rollback");
        policy=engine();assert policy.inspect(A,false,"!!!",wall,mono).allowed();
        assert policy.inspect(A,false,"!!!",wall,mono+1).allowed();
        assert !policy.inspect(A,false,"!!!",wall,mono+2).allowed();pass("Punctuation-only repeat spam is checked");
        policy=engine();assert !policy.inspect(A,false,"\u041f\u0440\u0438\u0432\u0435\u0442",wall,mono).allowed();
        assert policy.inspect(A,false,"clean \ud83d\ude00",wall,mono+1).allowed();
        assert !policy.inspect(A,false,"new\nline",wall,mono+2).allowed();
        assert !policy.inspect(A,false,"x".repeat(2049),wall,mono+3).allowed();
        pass("Latin policy preserves emoji and rejects controls/oversized input");
        policy=engine();assert !policy.inspect(A,false,"e.v.i.l.w.o.r.d",wall,mono).allowed();
        assert !policy.inspect(A,false,"clean",wall+1,mono+1).allowed();
        assert policy.inspect(A,true,"evilword",wall+1,mono+1).allowed();
        pass("Word violation mutes both paths; intentional permission bypass remains");
        PunishmentStore hugeLevel=new PunishmentStore(file());
        hugeLevel.put(A,new PunishmentStore.PlayerState(Integer.MAX_VALUE,0,wall));hugeLevel.flush(1,false);
        policy=new FilterEngine(hugeLevel,defaults());
        assert !policy.inspect(A,false,"bad",wall+1,mono).allowed();
        pass("Extreme legacy escalation level cannot overflow into a negative level");

        policy=engine();
        for(int i=0;i<FilterEngine.MAX_HISTORIES;i++)assert policy.inspect(new UUID(3,i),false,"first",wall,mono).allowed();
        assert !policy.inspect(A,false,"first",wall,mono).allowed()&&policy.histories()==FilterEngine.MAX_HISTORIES;
        policy.prune(mono+31_000_000_000L);assert policy.histories()==0;
        pass("Retained history has a fixed account cap and expiry pruning");

        policy=engine();
        assert policy.inspect(A,false,"kept",wall,mono).allowed();
        assert policy.inspect(A,false,"kept",wall,mono+1).allowed();
        for(int i=1;i<FilterEngine.MAX_DISTINCT;i++)assert policy.inspect(A,false,"unique"+i,wall,mono+2+i).allowed();
        assert !policy.inspect(A,false,"overflownew",wall,mono+100).allowed();
        assert !policy.inspect(A,false,"kept",wall,mono+101).allowed();
        pass("Distinct-message cap retains live keys instead of allowing rotation evasion");

        FilterSettings settings=defaults();
        assert PrivateCommands.extract("/tell Bob bad","tell",settings).equals("bad");
        assert PrivateCommands.extract("/minecraft:tell Bob bad","tell",settings).equals("bad");
        assert PrivateCommands.extract("/NORDCHAT:MSG Bob bad","msg",settings).equals("bad");
        assert PrivateCommands.extract("/customalias Bob bad","msg",settings).equals("bad");
        assert PrivateCommands.extract("/nordchat:r bad",null,settings).equals("bad");
        assert PrivateCommands.extract("/warp home",null,settings)==null;
        pass("Core, namespaced and command-map-resolved private aliases checked");
        try{config("spam:\n  window-seconds: 999999999999\n","words: []\n");throw new AssertionError();}catch(IOException expected){}
        try{config("punishments:\n  mute-durations-seconds: [-1]\n","words: []\n");throw new AssertionError();}catch(IOException expected){}
        try{config("{}\n","words: null\n");throw new AssertionError();}catch(IOException expected){}
        try{config("{}\n","typo: []\n");throw new AssertionError();}catch(IOException expected){}
        pass("Invalid bounds/durations/dictionary fail rather than silently disabling policy");

        MainThreadBridge<Integer,Integer> bridge=new MainThreadBridge<>(2);AtomicInteger processed=new AtomicInteger();
        var one=bridge.offer(1,100);var two=bridge.offer(2,100);assert bridge.offer(3,100)==null;
        bridge.drain(1,1,value->{processed.incrementAndGet();return value;});
        assert one.result.join()==1&&!two.result.isDone()&&bridge.size()==1;
        two.cancel();bridge.drain(1,2,value->{processed.incrementAndGet();return value;});assert processed.get()==1;
        pass("Main-thread queue enforces capacity/budget; cancelled request never executes");
        var expired=bridge.offer(3,10);bridge.drain(1,10,value->{processed.incrementAndGet();return value;});assert expired.result.isCancelled();
        var closing=bridge.offer(4,100);bridge.close();assert closing.result.isCancelled()&&bridge.offer(5,100)==null;
        pass("Expired/closed requests fail closed without hanging waiting chat workers");
        MainThreadBridge<Integer,Integer> exceptional=new MainThreadBridge<>(1);
        var broken=exceptional.offer(1,100);exceptional.drain(1,1,value->{throw new IllegalStateException();});
        assert broken.result.isCompletedExceptionally();pass("Handler failures wake waiting worker instead of losing its future");
        try(var paths=Files.list(f.getParent())){assert paths.noneMatch(p->p.toString().endsWith(".tmp"));}
        pass("Atomic save leaves no temporary files");
        System.out.println("ALL_FILTER_REGRESSION_TESTS_PASSED count="+passed);
    }
}
