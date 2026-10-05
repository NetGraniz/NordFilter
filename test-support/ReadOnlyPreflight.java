import java.nio.file.Path;
import java.lang.reflect.*;

/** Read-only validation of existing files. Does not start the plugin or a writer. */
public final class ReadOnlyPreflight {
    static Object call(Object instance,String method)throws Exception{
        Method m=instance.getClass().getDeclaredMethod(method);m.setAccessible(true);return m.invoke(instance);
    }
    public static void main(String[] args)throws Exception{
        Path directory=Path.of(args[0]);
        Class<?> store=Class.forName("com.nordfjell.nordfilter.PunishmentStore");
        Constructor<?> c=store.getDeclaredConstructor(Path.class);c.setAccessible(true);
        Object loaded=c.newInstance(directory.resolve("data.yml"));
        Class<?> settings=Class.forName("com.nordfjell.nordfilter.FilterSettings");
        Method load=settings.getDeclaredMethod("load",Path.class);load.setAccessible(true);
        Object config=load.invoke(null,directory);
        System.out.println("READ_ONLY_PREFLIGHT_OK records="+call(loaded,"recordCount")
                +" matcherNodes="+call(call(config,"matcher"),"nodes"));
    }
}
