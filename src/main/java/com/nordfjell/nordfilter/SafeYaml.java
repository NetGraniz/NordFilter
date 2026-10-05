package com.nordfjell.nordfilter;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class SafeYaml {
    static final int MAX_BYTES=16*1024*1024;
    static Yaml parser(){
        LoaderOptions options=new LoaderOptions();
        options.setAllowDuplicateKeys(false);options.setCodePointLimit(MAX_BYTES);
        options.setNestingDepthLimit(30);options.setMaxAliasesForCollections(20);
        return new Yaml(new SafeConstructor(options));
    }
    static Map<?,?> read(Path file,boolean allowMissing)throws IOException{
        if(allowMissing&&!Files.exists(file))return Map.of();
        if(!Files.isRegularFile(file)||Files.size(file)>MAX_BYTES)throw new IOException("Invalid YAML file");
        try{
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
            Object loaded=parser().load(text);
            // Blank/comment-only legacy files are empty; explicit YAML null is corruption.
            if(loaded==null&&parser().compose(new StringReader(text))==null)return Map.of();
            return map(loaded);
        }catch(IOException e){throw e;}catch(RuntimeException e){throw new IOException("Invalid YAML",e);}
    }
    static Map<?,?> map(Object value)throws IOException{
        if(!(value instanceof Map<?,?> result))throw new IOException("Expected YAML section");return result;
    }
    static Map<?,?> section(Map<?,?> root,String key)throws IOException{return root.containsKey(key)?map(root.get(key)):Map.of();}
    static long number(Map<?,?> map,String key,long fallback,long min,long max)throws IOException{
        if(!map.containsKey(key))return fallback;
        Object value=map.get(key);
        if(!(value instanceof Integer||value instanceof Long))throw new IOException("Invalid integer field");
        long result=((Number)value).longValue();
        if(result<min||result>max)throw new IOException("Integer field out of bounds");return result;
    }
    static boolean flag(Map<?,?> map,String key,boolean fallback)throws IOException{
        if(!map.containsKey(key))return fallback;
        if(!(map.get(key)instanceof Boolean result))throw new IOException("Invalid boolean field");return result;
    }
    static String text(Map<?,?> map,String key,String fallback)throws IOException{
        if(!map.containsKey(key))return fallback;
        if(!(map.get(key)instanceof String result)||result.length()>512||result.codePoints().anyMatch(Character::isISOControl))
            throw new IOException("Invalid message field");return result;
    }
    static UUID uuid(Object value)throws IOException{
        if(!(value instanceof String text))throw new IOException("Invalid UUID type");
        try{UUID id=UUID.fromString(text);if(!id.toString().equalsIgnoreCase(text))throw new IllegalArgumentException();return id;}
        catch(IllegalArgumentException e){throw new IOException("Invalid UUID",e);}
    }
}
