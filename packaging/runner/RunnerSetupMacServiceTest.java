import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Actual launchd behavior using a unique job, temporary home, no identity and no worlds. */
public class RunnerSetupMacServiceTest {
    static boolean locked(Path home)throws Exception {
        try(FileChannel channel=FileChannel.open(home.resolve("runner.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            try(var lock=channel.tryLock()){return lock==null;}
        }
    }
    public static void main(String[] args)throws Exception {
        String label="local.servermanager.runner.test-"+UUID.randomUUID();
        String uid=new String(new ProcessBuilder("/usr/bin/id","-u").start().getInputStream().readAllBytes()).trim();
        String job="gui/"+uid+"/"+label;
        Path temp=Files.createTempDirectory("runner-install-service-");String previous=System.getProperty("user.home");
        try {
            System.setProperty("user.home",temp.toString());
            Path target=temp.resolve("Applications/Runner & friend"),home=temp.resolve("data");
            var service=new RunnerSetup.SystemServices(label);
            RunnerSetup.install(Path.of(args[0]),target,home,true,service,s->{});
            Path plist=temp.resolve("Library/LaunchAgents/"+label+".plist");
            if(!Files.readString(plist).contains("Runner &amp; friend"))throw new AssertionError("XML path escaping");
            service.start(target,home);
            Path log=home.resolve("logs/runner.log");
            for(int i=0;i<100;i++) {
                if(Files.exists(log) && Files.readString(log).contains("이 컴퓨터는 아직 등록되지 않았습니다") && locked(home))break;
                Thread.sleep(200);
                if(i==99)throw new AssertionError("LaunchAgent didn't enter unregistered standby");
            }
            try {RunnerSetup.install(Path.of(args[0]),target,home,false,service,s->{});throw new AssertionError("Allowed active reinstall");}
            catch(java.io.IOException expected){}
            // Test job has no identity and cannot start any Minecraft process.
            new ProcessBuilder("/bin/launchctl","bootout",job).start().waitFor();
            for(int i=0;i<100 && locked(home);i++)Thread.sleep(100);
            if(locked(home))throw new AssertionError("Test supervisor did not exit");
            RunnerSetup.install(Path.of(args[0]),target,home,false,service,s->{});
            if(Files.exists(plist))throw new AssertionError("Autostart OFF left a plist");
            Process lookup=new ProcessBuilder("/bin/launchctl","print",job).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(lookup.waitFor()==0)throw new AssertionError("Autostart OFF left a loaded job");
            if(Files.exists(home.resolve("identity.json")))throw new AssertionError("Unexpected identity");
            System.out.println("Actual isolated Mac installer: copied bundled Java, escaped path, LaunchAgent standby, active reinstall rejection and autostart OFF passed.");
        } finally {
            new ProcessBuilder("/bin/launchctl","bootout",job).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor();
            System.setProperty("user.home",previous);RunnerSetup.deleteTree(temp);
        }
    }
}
