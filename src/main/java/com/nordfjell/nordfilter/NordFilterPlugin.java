package com.nordfjell.nordfilter;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NordFilterPlugin extends JavaPlugin implements Listener {
    private record ChatJob(Player player,Component message){}
    private record ReloadResult(FilterSettings settings,String error){}
    private static final int CHAT_CAPACITY=128,CHAT_BUDGET=16;
    private final MainThreadBridge<ChatJob,FilterEngine.Result> chatBridge=new MainThreadBridge<>(CHAT_CAPACITY);
    private final Map<UUID,Player> sessions=new HashMap<>();
    private final Map<UUID,Long> noticeAt=new HashMap<>();
    private final ArrayBlockingQueue<ReloadResult> reloadResults=new ArrayBlockingQueue<>(1);
    private final AtomicBoolean reloadPending=new AtomicBoolean();
    private final PlainTextComponentSerializer plainText=PlainTextComponentSerializer.plainText();
    private PunishmentStore punishmentStore;
    private FilterSettings settings;
    private FilterEngine engine;
    private ScheduledExecutorService writer;
    private BukkitTask pump;
    private volatile boolean accepting;
    private int ticks;
    private Path directory;
    private String reportedProblem="";

    @Override public void onEnable(){
        saveDefaultConfig();
        directory=getDataFolder().toPath();
        if(!Files.exists(directory.resolve("banwords.yml")))saveResource("banwords.yml",false);
        try{punishmentStore=new PunishmentStore(directory.resolve("data.yml"));}
        catch(IOException e){getLogger().severe("Invalid/unreadable data.yml; moderation fails closed, file not overwritten. Repair offline/restart. "+e.getClass().getSimpleName());}
        try{settings=FilterSettings.load(directory);}
        catch(IOException e){getLogger().severe("Invalid moderation configuration; chat/PM blocked until valid reload. "+e.getClass().getSimpleName());}
        if(punishmentStore!=null){
            if(settings!=null)engine=new FilterEngine(punishmentStore,settings);
            writer=Executors.newSingleThreadScheduledExecutor(task->{
                Thread thread=new Thread(task,"NordFilter-storage");thread.setDaemon(true);return thread;
            });
            writer.scheduleWithFixedDelay(()->{
                punishmentStore.flush(System.nanoTime(),false);
                String problem=punishmentStore.problem();
                if(!problem.equals(reportedProblem)){
                    if(problem.isEmpty())getLogger().info("Moderation storage recovered.");
                    else getLogger().severe("Moderation storage: "+problem);
                    reportedProblem=problem;
                }
            },100,100,TimeUnit.MILLISECONDS);
        }
        for(Player player:Bukkit.getOnlinePlayers())sessions.put(player.getUniqueId(),player);
        getServer().getPluginManager().registerEvents(this,this);
        pump=Bukkit.getScheduler().runTaskTimer(this,this::tick,1,1);
        accepting=true;
        if(engine!=null)getLogger().info("NordFilter 1.1.0 enabled: bounded main-thread checks and atomic background persistence.");
        else getLogger().severe("NordFilter started in fail-closed mode.");
    }
    @Override public void onDisable(){
        accepting=false;chatBridge.close();if(pump!=null)pump.cancel();reloadResults.clear();
        if(writer!=null){
            writer.execute(()->punishmentStore.flush(System.nanoTime(),true));writer.shutdown();
            try{if(!writer.awaitTermination(2,TimeUnit.SECONDS)){
                getLogger().severe("Moderation writer still active after two seconds; pending changes may remain.");writer.shutdownNow();
            }}catch(InterruptedException e){Thread.currentThread().interrupt();writer.shutdownNow();}
            if(punishmentStore.pendingCount()>0)getLogger().severe("Unsaved moderation changes: "+punishmentStore.pendingCount());
            punishmentStore.close();
        }
        sessions.clear();noticeAt.clear();
    }
    private void tick(){
        ReloadResult reload=reloadResults.poll();
        if(reload!=null){
            if(reload.settings()!=null){
                settings=reload.settings();
                if(punishmentStore!=null){
                    if(engine==null)engine=new FilterEngine(punishmentStore,settings);else engine.settings(settings);
                }
                getLogger().info("Moderation configuration reloaded.");
            }else getLogger().severe("Moderation reload rejected; previous settings retained. "+reload.error());
            reloadPending.set(false);
        }
        long now=System.nanoTime();
        chatBridge.drain(CHAT_BUDGET,now,this::inspectMain);
        if(++ticks%20==0&&engine!=null)engine.prune(now);
    }
    @EventHandler public void onJoin(PlayerJoinEvent event){
        sessions.put(event.getPlayer().getUniqueId(),event.getPlayer());
    }
    @EventHandler public void onQuit(PlayerQuitEvent event){
        UUID id=event.getPlayer().getUniqueId();
        if(sessions.get(id)==event.getPlayer()){sessions.remove(id);noticeAt.remove(id);}
        // Keep bounded duplicate history through a reconnect until the configured window expires.
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void onChat(AsyncChatEvent event){
        if(!accepting){event.viewers().clear();return;}
        FilterEngine.Result result;
        ChatJob job=new ChatJob(event.getPlayer(),event.message());
        if(Bukkit.isPrimaryThread())result=inspectMain(job);
        else{
            var ticket=chatBridge.offer(job,System.nanoTime()+2_000_000_000L);
            if(ticket==null){event.viewers().clear();return;}
            try{result=ticket.result.get(2,TimeUnit.SECONDS);}
            catch(InterruptedException e){Thread.currentThread().interrupt();ticket.cancel();event.viewers().clear();return;}
            catch(ExecutionException|TimeoutException|CancellationException e){ticket.cancel();event.viewers().clear();return;}
        }
        // Suppress recipients, not the signed event. Paper retains ownership of acknowledgment.
        if(!result.allowed())event.viewers().clear();
    }
    private FilterEngine.Result inspectMain(ChatJob job){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Moderation must run on main thread");
        Player player=job.player();UUID id=player.getUniqueId();
        if(!accepting||sessions.get(id)!=player||!player.isOnline())return FilterEngine.Result.block("");
        FilterEngine.Result result=engine==null?FilterEngine.Result.block("Moderation is unavailable; please try later.")
                :engine.inspect(id,player.hasPermission("nordfilter.bypass"),plainText.serialize(job.message()),System.currentTimeMillis(),System.nanoTime());
        if(!result.allowed()&&!result.message().isEmpty())notifyMain(player,result.message());
        return result;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onPrivateMessageCommand(PlayerCommandPreprocessEvent event){
        if(!Bukkit.isPrimaryThread()){event.setCancelled(true);return;}
        String label=PrivateCommands.label(event.getMessage());
        Command resolved=getServer().getCommandMap().getCommand(label);
        // Core commands remain recognized even when operator settings failed to initialize.
        String message=settings==null?corePrivateBody(event.getMessage())
                :PrivateCommands.extract(event.getMessage(),resolved==null?null:resolved.getName(),settings);
        if(message==null)return;
        var result=inspectMain(new ChatJob(event.getPlayer(),Component.text(message)));
        if(!result.allowed())event.setCancelled(true);
    }
    private String corePrivateBody(String raw){
        String label=PrivateCommands.label(raw);int colon=label.lastIndexOf(':');if(colon>=0)label=label.substring(colon+1);
        String[] first=(raw.startsWith("/")?raw.substring(1):raw).stripLeading().split("\\s+",2);
        if(first.length<2)return null;
        if(Set.of("reply","r","last").contains(label))return first[1];
        if(Set.of("msg","tell","whisper","pm","w").contains(label)){
            String[] parts=first[1].stripLeading().split("\\s+",2);return parts.length==2?parts[1]:null;
        }
        return null;
    }
    private void notifyMain(Player player,String text){
        long now=System.nanoTime();Long previous=noticeAt.get(player.getUniqueId());
        if(previous!=null&&now-previous<250_000_000L)return;
        noticeAt.put(player.getUniqueId(),now);
        player.sendMessage(Component.text(text,NamedTextColor.RED));
    }
    private UUID knownTarget(String text){
        Player online=Bukkit.getPlayerExact(text);if(online!=null)return online.getUniqueId();
        try{
            UUID id=UUID.fromString(text);
            return id.toString().equalsIgnoreCase(text)&&punishmentStore!=null&&punishmentStore.known(id)?id:null;
        }catch(IllegalArgumentException ignored){
            if(!text.matches("[A-Za-z0-9_.-]{1,32}"))return null;
            OfflinePlayer cached=Bukkit.getOfflinePlayerIfCached(text);return cached==null?null:cached.getUniqueId();
        }
    }
    @Override public boolean onCommand(@NotNull CommandSender sender,@NotNull Command command,@NotNull String label,@NotNull String[] args){
        if(!Bukkit.isPrimaryThread()){getLogger().warning("Rejected asynchronous moderation command callback.");return true;}
        if(!sender.hasPermission("nordfilter.admin")){
            sender.sendMessage(Component.text("You do not have permission.",NamedTextColor.RED));return true;
        }
        if(args.length==1&&args[0].equalsIgnoreCase("health")){
            sender.sendMessage(Component.text("NordFilter: "+(engine!=null&&punishmentStore.available()?"ready":"blocked")
                    +", chat pending="+chatBridge.size()+", writes pending="+(punishmentStore==null?-1:punishmentStore.pendingCount())
                    +", problem="+(punishmentStore==null?"initialization failure":punishmentStore.problem())));return true;
        }
        if(args.length==1&&args[0].equalsIgnoreCase("reload")){
            if(writer==null){sender.sendMessage(Component.text("Repair data.yml offline and restart; reload cannot reset failed storage."));return true;}
            if(!reloadPending.compareAndSet(false,true)){sender.sendMessage(Component.text("Reload already pending."));return true;}
            writer.execute(()->{
                ReloadResult result;
                try{result=new ReloadResult(FilterSettings.load(directory),null);}
                catch(IOException|RuntimeException e){result=new ReloadResult(null,e.getClass().getSimpleName());}
                if(accepting)reloadResults.offer(result);
            });
            sender.sendMessage(Component.text("Reload queued. Check console/health for the result."));return true;
        }
        if(args.length==2&&Set.of("status","unmute","reset").contains(args[0].toLowerCase(Locale.ROOT))){
            if(punishmentStore==null){sender.sendMessage(Component.text("Moderation storage unavailable; repair offline/restart."));return true;}
            UUID id=knownTarget(args[1]);
            if(id==null){sender.sendMessage(Component.text("Player has not joined this server before.",NamedTextColor.RED));return true;}
            if(args[0].equalsIgnoreCase("status")){
                var state=punishmentStore.get(id);long remaining=state.muteUntil()>System.currentTimeMillis()?state.muteUntil()-System.currentTimeMillis():0;
                sender.sendMessage(Component.text(args[1]+": level "+state.level()+", mute remaining "+(remaining==0?"none":FilterEngine.formatDuration(remaining))
                        +". Pending writes="+punishmentStore.pendingCount()));return true;
            }
            if(!punishmentStore.available()){sender.sendMessage(Component.text("Storage unhealthy; change rejected."));return true;}
            boolean accepted=args[0].equalsIgnoreCase("reset")?punishmentStore.reset(id)
                    :punishmentStore.put(id,punishmentStore.desired(id).withoutMute());
            sender.sendMessage(Component.text(accepted?"Change queued; existing mute remains until persisted. Verify with status."
                    :"Persistence capacity exceeded; change rejected.",accepted?NamedTextColor.YELLOW:NamedTextColor.RED));return true;
        }
        sender.sendMessage(Component.text("Usage: /nordfilter <health|reload|status|unmute|reset> [player]"));return true;
    }
}
