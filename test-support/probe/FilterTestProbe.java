package com.nordfjell.nordfilter.test;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.command.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.util.*;

/** LOCAL fault injection/diagnostics only. Never packaged in the release. */
public final class FilterTestProbe extends JavaPlugin implements Listener{
    private Object originalPersister;private volatile int delay;private volatile boolean fail;
    @Override public void onEnable(){
        getServer().getPluginManager().registerEvents(this,this);getCommand("fptest").setExecutor(this);
        Command alias=new Command("msg"){
            @Override public boolean execute(CommandSender sender,String label,String[] args){
                return Bukkit.dispatchCommand(sender,"nordchat:msg "+String.join(" ",args));
            }
        };
        alias.setAliases(List.of("fpm"));getServer().getCommandMap().register("filtertest",alias);
    }
    @EventHandler(priority=EventPriority.LOW,ignoreCancelled=true)
    public void rewrite(PlayerCommandPreprocessEvent event){
        if(event.getMessage().startsWith("/froute "))event.setMessage("/nordchat:msg "+event.getMessage().substring(8));
    }
    private Object plugin(){return Bukkit.getPluginManager().getPlugin("NordFilter");}
    private Object field(Object target,String name)throws Exception{
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private Object invoke(Object target,String name,Object...args)throws Exception{
        Method found=Arrays.stream(target.getClass().getDeclaredMethods()).filter(m->m.getName().equals(name)&&m.getParameterCount()==args.length).findFirst().orElseThrow();
        found.setAccessible(true);return found.invoke(target,args);
    }
    private void faults()throws Exception{
        Object store=field(plugin(),"punishmentStore");Field persister=store.getClass().getDeclaredField("persister");persister.setAccessible(true);
        if(originalPersister==null)originalPersister=persister.get(store);
        Class<?> type=persister.getType();
        Object replacement=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            if(method.getName().equals("save")){
                int wait=delay;if(wait>0)Thread.sleep(wait);
                if(fail)throw new java.io.IOException("LOCAL synthetic persistence fault");
                try{method.setAccessible(true);return method.invoke(originalPersister,args);}catch(InvocationTargetException e){throw e.getCause();}
            }
            return null;
        });
        persister.set(store,replacement);
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(sender instanceof Player){sender.sendMessage("Console only.");return true;}
        try{
            switch(args[0]){
                case "fault"->{fail=Boolean.parseBoolean(args[1]);faults();}
                case "slow"->{delay=Integer.parseInt(args[1]);faults();}
                case "state"->{
                    Object store=field(plugin(),"punishmentStore"),bridge=field(plugin(),"chatBridge"),engine=field(plugin(),"engine");
                    long workers=Thread.getAllStackTraces().keySet().stream().filter(t->t.isAlive()&&t.getName().equals("NordFilter-storage")).count();
                    getLogger().info("FSTATE "+args[1]+" pending="+(store==null?-1:invoke(store,"pendingCount"))
                            +" records="+(store==null?-1:invoke(store,"recordCount"))+" writers="+workers
                            +" bridge="+invoke(bridge,"size")+" histories="+(engine==null?-1:invoke(engine,"histories"))
                            +" problem="+(store==null?"INITIALIZATION_FAILED":invoke(store,"problem")));
                }
                case "player"->{
                    Object store=field(plugin(),"punishmentStore");
                    Player player=Bukkit.getPlayerExact(args[1]);Object state=invoke(store,"get",player.getUniqueId());
                    getLogger().info("PSTATE "+args[2]+" level="+invoke(state,"level")+" until="+invoke(state,"muteUntil"));
                }
                case "permission"->Bukkit.getPlayerExact(args[1]).addAttachment(this,args[2],Boolean.parseBoolean(args[3]));
                case "direct"->{
                    JavaPlugin target=(JavaPlugin)plugin();
                    target.onCommand(Bukkit.getPlayerExact(args[1]),target.getCommand("nordfilter"),"nordfilter",Arrays.copyOfRange(args,2,args.length));
                }
                default->throw new IllegalArgumentException("Unknown probe command");
            }
            getLogger().info("FPTEST_OK "+String.join(" ",args));
        }catch(Exception e){getLogger().severe("FPTEST_FAILED "+e);}
        return true;
    }
}
