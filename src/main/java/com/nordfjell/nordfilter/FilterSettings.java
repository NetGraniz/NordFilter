package com.nordfjell.nordfilter;
import java.io.*;
import java.nio.file.*;
import java.util.*;
record FilterSettings(boolean enforceLatin,String languageMessage,boolean spamEnabled,int identicalLimit,
                      long spamWindowNanos,List<Long> muteDurations,long resetMillis,String mutedMessage,
                      String violationMessage,Set<String> targetCommands,Set<String> messageCommands,WordMatcher matcher){
    static FilterSettings load(Path directory)throws IOException{
        Map<?,?> root=SafeYaml.read(directory.resolve("config.yml"),false);
        Map<?,?> language=SafeYaml.section(root,"language"),spam=SafeYaml.section(root,"spam"),punishments=SafeYaml.section(root,"punishments");
        Map<?,?> commands=SafeYaml.section(root,"private-message-commands");
        Map<?,?> words=SafeYaml.read(directory.resolve("banwords.yml"),false);
        for(Object key:words.keySet())if(!"words".equals(key))throw new IOException("Unknown banword field");
        if(!words.containsKey("words"))throw new IOException("Banwords list required");
        Set<String> normalized=new HashSet<>();int total=0;
        Object values=words.getOrDefault("words",null);
        if(values!=null){
            if(!(values instanceof List<?> list)||list.size()>5000)throw new IOException("Banword count/type limit");
            for(Object value:list){
                if(!(value instanceof String word)||word.length()>256)throw new IOException("Invalid banword");
                String canonical=FilterEngine.normalize(word);total+=canonical.length();
                if(total>100000)throw new IOException("Banword character limit");
                if(!canonical.isBlank())normalized.add(canonical);
            }
        }else if(words.containsKey("words"))throw new IOException("Null banwords");
        List<Long> durations=new ArrayList<>();
        if(punishments.containsKey("mute-durations-seconds")){
            Object value=punishments.get("mute-durations-seconds");
            if(!(value instanceof List<?> list)||list.isEmpty()||list.size()>100)throw new IOException("Invalid mute durations");
            for(Object duration:list){
                if(!(duration instanceof Integer||duration instanceof Long))throw new IOException("Invalid mute duration");
                long seconds=((Number)duration).longValue();
                if(seconds<1||seconds>31_536_000L)throw new IOException("Mute duration limit");
                durations.add(seconds*1000L);
            }
        }else durations.addAll(List.of(30_000L,300_000L,3_600_000L,86_400_000L));
        Set<String> targets=aliases(commands,"target-then-message",Set.of("msg","whisper","pm","w","tell"));
        Set<String> bodies=aliases(commands,"message-only",Set.of("reply","r","last"));
        if(targets.stream().anyMatch(bodies::contains))throw new IOException("Ambiguous command alias");
        return new FilterSettings(SafeYaml.flag(language,"enforce-latin-script",true),
                SafeYaml.text(language,"rejection-message","Please use Latin letters."),
                SafeYaml.flag(spam,"enabled",true),(int)SafeYaml.number(spam,"identical-message-limit",2,1,100),
                SafeYaml.number(spam,"window-seconds",30,1,3600)*1_000_000_000L,List.copyOf(durations),
                SafeYaml.number(punishments,"violation-reset-hours",168,1,87600)*3_600_000L,
                SafeYaml.text(punishments,"muted-message","You are muted for {time}."),
                SafeYaml.text(punishments,"violation-message","{reason} You have been muted for {time}."),
                targets,bodies,new WordMatcher(normalized));
    }
    private static Set<String> aliases(Map<?,?> config,String key,Set<String> mandatory)throws IOException{
        Set<String> result=new HashSet<>(mandatory);
        if(config.containsKey(key)){
            if(!(config.get(key)instanceof List<?> list)||list.size()>128)throw new IOException("Invalid command aliases");
            for(Object value:list){
                if(!(value instanceof String name)||!name.matches("[A-Za-z0-9_-]{1,64}"))throw new IOException("Invalid command alias");
                result.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(result);
    }
}
