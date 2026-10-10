import java.lang.reflect.Method;

public class SnapRun {
    public static void main(String[] a) throws Exception {
        Class<?> c = Class.forName(
            "com.cloudinfra.test.support.diagnostics.HostLoadDiagnosticsExtension");
        Method m = c.getDeclaredMethod("renderSnapshot", String.class, String.class);
        m.setAccessible(true);
        System.out.println((String) m.invoke(null, "manual-probe", "manual"));
    }
}
