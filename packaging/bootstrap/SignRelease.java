import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
public class SignRelease {
 public static void main(String[] args)throws Exception {
  Signature s=Signature.getInstance("Ed25519");s.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(Path.of(args[0])))));s.update(Files.readAllBytes(Path.of(args[1])));Files.write(Path.of(args[2]),s.sign());
 }
}
