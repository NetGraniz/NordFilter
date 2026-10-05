package com.nordfjell.nordfilter;
import java.util.*;

/** Immutable Aho-Corasick automaton: a normalized input is scanned once. */
final class WordMatcher {
    private static final class Node {final Map<Character,Integer> next=new HashMap<>();int failure;boolean match;}
    private final List<Node> nodes=new ArrayList<>();
    WordMatcher(Collection<String> words){
        nodes.add(new Node());
        for(String word:words){
            int state=0;
            for(char ch:word.toCharArray()){
                Node node=nodes.get(state);Integer next=node.next.get(ch);
                if(next==null){next=nodes.size();node.next.put(ch,next);nodes.add(new Node());}
                state=next;
            }
            if(!word.isEmpty())nodes.get(state).match=true;
        }
        ArrayDeque<Integer> queue=new ArrayDeque<>(nodes.get(0).next.values());
        while(!queue.isEmpty()){
            int parent=queue.removeFirst();
            for(var edge:nodes.get(parent).next.entrySet()){
                char ch=edge.getKey();int child=edge.getValue(),fallback=nodes.get(parent).failure;
                while(fallback!=0&&!nodes.get(fallback).next.containsKey(ch))fallback=nodes.get(fallback).failure;
                nodes.get(child).failure=nodes.get(fallback).next.getOrDefault(ch,0);
                nodes.get(child).match|=nodes.get(nodes.get(child).failure).match;
                queue.addLast(child);
            }
        }
    }
    boolean matches(String text){
        int state=0;
        for(char ch:text.toCharArray()){
            while(state!=0&&!nodes.get(state).next.containsKey(ch))state=nodes.get(state).failure;
            state=nodes.get(state).next.getOrDefault(ch,0);
            if(nodes.get(state).match)return true;
        }
        return false;
    }
    int nodes(){return nodes.size();}
}
