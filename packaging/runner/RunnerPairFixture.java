import java.nio.file.*;
/** Emits sensitive output deliberately to prove the setup process discards it. */
public class RunnerPairFixture {
    public static void main(String[] args)throws Exception {
        String code=new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
        if(java.util.Arrays.stream(args).anyMatch(s->s.contains("fixture-code"))) throw new AssertionError("secret in argv");
        System.out.println(code);System.err.println(code);
        if(code.contains("failing")) System.exit(1);
        Files.writeString(Path.of(System.getenv("SERVER_MANAGER_RUNNER_HOME"),"identity.json"),"fixture-identity");
    }
}
