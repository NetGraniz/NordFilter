package com.nordfjell.nordfilter;
import java.util.concurrent.*;
import java.util.function.Function;

/** Bounded handoff. Timed-out queued requests cannot apply to a later player session. */
final class MainThreadBridge<T,R>{
    static final class Ticket<T,R>{
        final T payload;
        final long deadline;
        final CompletableFuture<R> result=new CompletableFuture<>();
        Ticket(T payload,long deadline){this.payload=payload;this.deadline=deadline;}
        void cancel(){result.cancel(false);}
    }
    private final ArrayBlockingQueue<Ticket<T,R>> queue;
    private boolean closed;
    MainThreadBridge(int capacity){queue=new ArrayBlockingQueue<>(capacity);}
    synchronized Ticket<T,R> offer(T payload,long deadline){
        if(closed)return null;
        Ticket<T,R> ticket=new Ticket<>(payload,deadline);
        return queue.offer(ticket)?ticket:null;
    }
    int size(){return queue.size();}
    void drain(int budget,long now,Function<T,R> action){
        for(int i=0;i<budget;i++){
            Ticket<T,R> ticket=queue.poll();if(ticket==null)return;
            if(ticket.result.isDone()||now-ticket.deadline>=0){ticket.cancel();continue;}
            try{ticket.result.complete(action.apply(ticket.payload));}
            catch(RuntimeException e){ticket.result.completeExceptionally(e);}
        }
    }
    synchronized void close(){
        closed=true;Ticket<T,R> ticket;while((ticket=queue.poll())!=null)ticket.cancel();
    }
}
