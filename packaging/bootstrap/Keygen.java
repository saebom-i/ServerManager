import java.nio.file.*;
import java.security.*;
public class Keygen {
 public static void main(String[] args)throws Exception {
  Path dir=Path.of(args[0]);if(Files.exists(dir.resolve("private.der")))return;
  KeyPair pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  Files.write(dir.resolve("private.der"),pair.getPrivate().getEncoded());
  Files.write(Path.of(args[1]),pair.getPublic().getEncoded());
 }
}
