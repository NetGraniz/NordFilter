package com.nordfjell.nordfilter;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded handoff. Timed-out queued requests cannot apply to a later player session. */
final class MainThreadBridge<T,R>{
    static final class Ticket<T,R>{
        final T payload;
        final long deadline;
        final CompletableFuture<R> result=new CompletableFuture<>();
        final AtomicBoolean released=new AtomicBoolean();
        Runnable release;
        Ticket(T payload,long deadline){this.payload=payload;this.deadline=deadline;}
        void cancel(){result.cancel(false);}
        void release(){if(released.compareAndSet(false,true))release.run();}
        void complete(R value){try{result.complete(value);}finally{release();}}
    }
    private final ArrayBlockingQueue<Ticket<T,R>> queue;
    private final Semaphore permits;
    private final Set<Ticket<T,R>> outstanding=ConcurrentHashMap.newKeySet();
    private boolean closed;
    MainThreadBridge(int capacity){queue=new ArrayBlockingQueue<>(capacity);permits=new Semaphore(capacity);}
    synchronized Ticket<T,R> offer(T payload,long deadline){
        if(closed||!permits.tryAcquire())return null;
        Ticket<T,R> ticket=new Ticket<>(payload,deadline);
        ticket.release=()->{outstanding.remove(ticket);permits.release();};outstanding.add(ticket);
        if(queue.offer(ticket))return ticket;
        ticket.cancel();ticket.release();return null;
    }
    int size(){return outstanding.size();}
    void drain(int budget,long now,Function<T,R> action){
        drainAsync(budget,now,ticket -> ticket.complete(action.apply(ticket.payload)));
    }
    void drainAsync(int budget,long now,Consumer<Ticket<T,R>> action){
        for(int i=0;i<budget;i++){
            Ticket<T,R> ticket=queue.poll();if(ticket==null)return;
            if(ticket.result.isDone()||now-ticket.deadline>=0){ticket.cancel();ticket.release();continue;}
            try{action.accept(ticket);}
            catch(RuntimeException e){ticket.result.completeExceptionally(e);ticket.release();}
        }
    }
    synchronized void close(){
        closed=true;queue.clear();for(var ticket:outstanding){ticket.cancel();ticket.release();}
    }
}
