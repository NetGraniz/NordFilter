package com.nordfjell.nordfilter;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure store. Pending restrictions apply immediately; releases only after atomic commit. */
final class PunishmentStore {
    static final int MAX_RECORDS=50000,MAX_PENDING=4096;
    @FunctionalInterface interface Persister {void save(Path file,byte[] data)throws IOException;}
    static final PlayerState EMPTY=new PlayerState(0,0,0);
    record PlayerState(int level,long muteUntil,long lastViolation){
        PlayerState{
            if(level<0||muteUntil<0||lastViolation<0)throw new IllegalArgumentException("Invalid punishment");
        }
        PlayerState withoutMute(){return new PlayerState(level,0,lastViolation);}
        PlayerState withViolation(int level,long until,long now){return new PlayerState(level,until,now);}
        static PlayerState stricter(PlayerState first,PlayerState second){
            return new PlayerState(Math.max(first.level,second.level),Math.max(first.muteUntil,second.muteUntil),
                    Math.max(first.lastViolation,second.lastViolation));
        }
    }
    private record Change(PlayerState state){}
    private final Path file;
    private final Persister persister;
    private final Object pendingLock=new Object(),writerLock=new Object();
    private volatile Map<UUID,PlayerState> committed=Map.of();
    private final Map<UUID,Change> pending=new LinkedHashMap<>();
    private final Map<UUID,PlayerState> heldRestrictions=new HashMap<>();
    private volatile String problem="";
    private boolean overflow,closed;
    private long retryAt;
    private int pendingAdditions;
    PunishmentStore(Path file)throws IOException{this(file,PunishmentStore::atomicSave);}
    PunishmentStore(Path file,Persister persister)throws IOException{
        this.file=file.toAbsolutePath().normalize();this.persister=persister;load();
    }
    private void load()throws IOException{
        Map<?,?> root=SafeYaml.read(file,true);
        for(Object key:root.keySet())if(!"players".equals(key))throw new IOException("Unknown punishment root");
        Map<?,?> players=SafeYaml.section(root,"players");
        if(players.size()>MAX_RECORDS)throw new IOException("Punishment record limit");
        Map<UUID,PlayerState> next=new HashMap<>();
        for(var entry:players.entrySet()){
            UUID id=SafeYaml.uuid(entry.getKey());Map<?,?> value=SafeYaml.map(entry.getValue());
            for(Object key:value.keySet())if(!Set.of("level","mute-until","last-violation").contains(key))
                throw new IOException("Unknown punishment field");
            PlayerState state=new PlayerState((int)SafeYaml.number(value,"level",0,0,Integer.MAX_VALUE),
                    SafeYaml.number(value,"mute-until",0,0,Long.MAX_VALUE),
                    SafeYaml.number(value,"last-violation",0,0,Long.MAX_VALUE));
            if(next.put(id,state)!=null)throw new IOException("Duplicate punishment UUID");
        }
        committed=Map.copyOf(next);
    }
    PlayerState get(UUID id){
        synchronized(pendingLock){
            PlayerState durable=committed.getOrDefault(id,EMPTY),held=heldRestrictions.get(id);
            return held==null?durable:PlayerState.stricter(durable,held);
        }
    }
    PlayerState desired(UUID id){
        synchronized(pendingLock){Change change=pending.get(id);return change==null?committed.getOrDefault(id,EMPTY)
                :change.state()==null?EMPTY:change.state();}
    }
    boolean put(UUID id,PlayerState state){return offer(id,new Change(Objects.requireNonNull(state)));}
    boolean reset(UUID id){return offer(id,new Change(null));}
    private boolean offer(UUID id,Change change){
        Objects.requireNonNull(id);
        synchronized(pendingLock){
            if(closed||overflow)return false;
            Change previous=pending.get(id);
            if(previous!=null&&Objects.equals(previous.state(),change.state()))return true;
            if(previous==null&&Objects.equals(committed.get(id),change.state()))return true;
            if(previous==null&&!committed.containsKey(id)&&EMPTY.equals(change.state()))return true;
            if(previous==null&&pending.size()>=MAX_PENDING){
                overflow=true;problem="pending capacity exceeded; reconciliation required";return false;
            }
            // Count only new distinct records; replacements/deletions do not increase the bound.
            if(change.state()!=null&&!committed.containsKey(id)){
                int additions=pendingAdditions;
                if(previous==null||previous.state()==null)additions++;
                if(committed.size()+additions>MAX_RECORDS){
                    overflow=true;problem="record capacity exceeded; reconciliation required";return false;
                }
            }
            if(!committed.containsKey(id)){
                if(previous!=null&&previous.state()!=null)pendingAdditions--;
                if(change.state()!=null)pendingAdditions++;
            }
            if(change.state()!=null)heldRestrictions.merge(id,change.state(),PlayerState::stricter);
            pending.put(id,change);return true;
        }
    }
    int pendingCount(){synchronized(pendingLock){return pending.size();}}
    int recordCount(){return committed.size();}
    boolean known(UUID id){synchronized(pendingLock){return committed.containsKey(id)||pending.containsKey(id);}}
    String problem(){return problem;}
    boolean available(){synchronized(pendingLock){return !closed&&problem.isEmpty();}}
    boolean flush(long now,boolean force){
        synchronized(writerLock){
            Map<UUID,Change> batch;
            synchronized(pendingLock){
                if(pending.isEmpty()||(!force&&retryAt!=0&&now-retryAt<0))return false;
                batch=new LinkedHashMap<>(pending);
            }
            Map<UUID,PlayerState> next=new HashMap<>(committed);
            batch.forEach((id,change)->{if(change.state()==null)next.remove(id);else next.put(id,change.state());});
            try{
                Map<UUID,PlayerState> immutable=Map.copyOf(next);
                persister.save(file,encode(immutable));
                synchronized(pendingLock){
                    committed=immutable;
                    batch.forEach((id,change)->{
                        if(pending.get(id)==change){pending.remove(id);heldRestrictions.remove(id);}
                    });
                    pendingAdditions=0;
                    for(var entry:pending.entrySet())if(entry.getValue().state()!=null&&!committed.containsKey(entry.getKey()))pendingAdditions++;
                    retryAt=0;if(!overflow)problem="";
                }
                return true;
            }catch(IOException|RuntimeException e){
                synchronized(pendingLock){
                    retryAt=now+5_000_000_000L;
                    if(!overflow)problem="save failed ("+e.getClass().getSimpleName()+"); retry pending";
                }
                return false;
            }
        }
    }
    void close(){synchronized(pendingLock){closed=true;}}
    private static byte[] encode(Map<UUID,PlayerState> records)throws IOException{
        Map<String,Object> players=new TreeMap<>();
        records.forEach((id,state)->{
            Map<String,Object> value=new LinkedHashMap<>();
            value.put("level",state.level());value.put("mute-until",state.muteUntil());value.put("last-violation",state.lastViolation());
            players.put(id.toString(),value);
        });
        byte[] bytes=SafeYaml.parser().dump(Map.of("players",players)).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>SafeYaml.MAX_BYTES)throw new IOException("Punishment file size limit");
        return bytes;
    }
    private static void atomicSave(Path file,byte[] data)throws IOException{
        Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),"nordfilter-",".tmp");
        try{
            try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE)){
                ByteBuffer bytes=ByteBuffer.wrap(data);while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
            }
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temp);}
    }
}
