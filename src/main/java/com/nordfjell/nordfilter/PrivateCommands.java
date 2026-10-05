package com.nordfjell.nordfilter;
import java.util.Locale;
final class PrivateCommands{
    static String label(String raw){
        String command=(raw.startsWith("/")?raw.substring(1):raw).stripLeading();
        return command.split("\\s+",2)[0].toLowerCase(Locale.ROOT);
    }
    static String extract(String raw,String canonical,FilterSettings settings){
        String command=(raw.startsWith("/")?raw.substring(1):raw).stripLeading();
        String[] first=command.split("\\s+",2);if(first.length<2)return null;
        String label=first[0].toLowerCase(Locale.ROOT);
        int colon=label.lastIndexOf(':');if(colon>=0)label=label.substring(colon+1);
        String resolved=canonical==null?"":canonical.toLowerCase(Locale.ROOT);
        if(settings.messageCommands().contains(label)||settings.messageCommands().contains(resolved))return first[1];
        if(settings.targetCommands().contains(label)||settings.targetCommands().contains(resolved)){
            String[] body=first[1].stripLeading().split("\\s+",2);
            return body.length==2?body[1]:null;
        }
        return null;
    }
}
