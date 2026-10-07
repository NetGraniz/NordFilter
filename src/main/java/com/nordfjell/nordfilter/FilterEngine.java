package com.nordfjell.nordfilter;
import java.text.Normalizer;
import java.util.*;

/** Main-thread policy state, bounded retained history; no Bukkit or disk I/O. */
final class FilterEngine {
    static final int MAX_BODY=2048,MAX_NORMALIZED=8192,MAX_HISTORIES=4096,MAX_DISTINCT=64;
    record Result(boolean allowed,String message){
        static Result allow(){return new Result(true,"");}
        static Result block(String reason){return new Result(false,reason);}
    }
    private static final class History{
        final LinkedHashMap<String,ArrayDeque<Long>> recent=new LinkedHashMap<>();
        long lastSeen;
    }
    private final PunishmentStore store;
    private FilterSettings settings;
    private final Map<UUID,History> histories=new HashMap<>();
    FilterEngine(PunishmentStore store,FilterSettings settings){this.store=store;this.settings=settings;}
    synchronized void settings(FilterSettings next){settings=next;histories.clear();}
    synchronized void prune(long now){histories.values().removeIf(history->now-history.lastSeen>settings.spamWindowNanos());}
    synchronized int histories(){return histories.size();}
    synchronized Result inspect(UUID id,boolean bypass,String message,long wall,long monotonic){
        if(!store.available())return Result.block("Moderation storage is unavailable; please try again later.");
        if(bypass)return Result.allow();
        if(message==null||message.isBlank()||message.length()>MAX_BODY
                ||message.codePoints().anyMatch(cp->Character.isISOControl(cp)||cp==0x2028||cp==0x2029))
            return Result.block("Invalid or oversized message.");
        PunishmentStore.PlayerState state=store.get(id);
        if(state.muteUntil()>wall)return Result.block(settings.mutedMessage().replace("{time}",formatDuration(state.muteUntil()-wall)));
        if(settings.enforceLatin()&&message.codePoints().anyMatch(cp->Character.isLetter(cp)
                &&Character.UnicodeScript.of(cp)!=Character.UnicodeScript.LATIN))
            return Result.block(settings.languageMessage());
        String normalized=normalize(message);
        if(normalized.length()>MAX_NORMALIZED)return Result.block("Normalized message size limit.");
        if(settings.matcher().matches(normalized))return violation(id,"Blocked word.",wall);
        if(settings.spamEnabled()){
            History history=histories.get(id);
            if(history==null){
                prune(monotonic);
                if(histories.size()>=MAX_HISTORIES)return Result.block("Moderation history capacity exceeded; please retry later.");
                history=new History();histories.put(id,history);
            }
            history.lastSeen=monotonic;
            String key=normalized.isBlank()?message.strip():normalized;
            Iterator<Map.Entry<String,ArrayDeque<Long>>> entries=history.recent.entrySet().iterator();
            while(entries.hasNext()){
                var timestamps=entries.next().getValue();
                while(!timestamps.isEmpty()&&monotonic-timestamps.peekFirst()>settings.spamWindowNanos())timestamps.removeFirst();
                if(timestamps.isEmpty())entries.remove();
            }
            ArrayDeque<Long> timestamps=history.recent.get(key);
            if(timestamps==null){
                // Do not evict live keys: rotating many unique messages must not erase repeats.
                if(history.recent.size()>=MAX_DISTINCT)return Result.block("Moderation message history capacity exceeded; please retry later.");
                timestamps=new ArrayDeque<>();history.recent.put(key,timestamps);
            }
            if(timestamps.size()>=settings.identicalLimit()){histories.remove(id);return violation(id,"Repeated message.",wall);}
            timestamps.addLast(monotonic);
        }
        return Result.allow();
    }
    private Result violation(UUID id,String reason,long now){
        var previous=store.get(id);int level=previous.level();
        if(previous.lastViolation()==0||now-previous.lastViolation()>settings.resetMillis())level=0;
        long duration=settings.muteDurations().get(Math.min(level,settings.muteDurations().size()-1));
        long until=now>Long.MAX_VALUE-duration?Long.MAX_VALUE:now+duration;
        if(!store.put(id,new PunishmentStore.PlayerState((int)Math.min((long)level+1,settings.muteDurations().size()),until,now)))
            return Result.block("Moderation persistence capacity exceeded; please try later.");
        return Result.block(settings.violationMessage().replace("{reason}",reason).replace("{time}",formatDuration(duration)));
    }
    static String normalize(String value){
        String decomposed=Normalizer.normalize(value,Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        StringBuilder result=new StringBuilder();
        decomposed.codePoints().filter(Character::isLetterOrDigit).forEach(result::appendCodePoint);return result.toString();
    }
    static String formatDuration(long millis){
        long seconds=millis/1000+(millis%1000==0?0:1);seconds=Math.max(1,seconds);
        if(seconds<60)return seconds+"s";
        long minutes=seconds/60+(seconds%60==0?0:1);
        return minutes<60?minutes+"m":(minutes/60+(minutes%60==0?0:1))+"h";
    }
}
