import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stands in for "mvn verify" in ValidationAgentTest: writes the reports a real build leaves behind.
 * With any argument it fails like a compile error instead (exit 1, no reports).
 */
class FakeBuild {
  public static void main(String[] args) throws Exception {
    if (args.length > 0) {
      System.out.println("[INFO] Compiling 42 source files");
      System.out.println("[ERROR] QrController.java:[3,1] ';' expected");
      System.out.println(
          "[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 7.636 s <<< FAILURE!"
              + " -- in com.acme.QrIT");
      System.out.println("[ERROR] com.acme.QrIT.works -- Time elapsed: 0.103 s <<< FAILURE!");
      System.out.println("[INFO] BUILD FAILURE");
      System.exit(1);
    }
    Path surefire = Files.createDirectories(Path.of("shortener-service/target/surefire-reports"));
    Files.writeString(
        surefire.resolve("TEST-com.acme.shortener.qr.QrTest.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="com.acme.shortener.qr.QrTest" tests="3" failures="1" errors="0" skipped="1">
          <testcase classname="com.acme.shortener.qr.QrTest" name="rendersPng"/>
          <testcase classname="com.acme.shortener.qr.QrTest" name="rejectsHugeSizes">
            <failure message="expected 400 but was 500" type="java.lang.AssertionError">trace</failure>
          </testcase>
          <testcase classname="com.acme.shortener.qr.QrTest" name="later"><skipped/></testcase>
        </testsuite>
        """);
    Path failsafe = Files.createDirectories(Path.of("shortener-service/target/failsafe-reports"));
    Files.writeString(
        failsafe.resolve("TEST-com.acme.shortener.qr.QrApiIT.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="com.acme.shortener.qr.QrApiIT" tests="2" failures="0" errors="0" skipped="0">
          <testcase classname="com.acme.shortener.qr.QrApiIT" name="ok"/>
          <testcase classname="com.acme.shortener.qr.QrApiIT" name="notFound"/>
        </testsuite>
        """);
    Path jacoco = Files.createDirectories(Path.of("shortener-service/target/site/jacoco"));
    Files.writeString(
        jacoco.resolve("jacoco.csv"),
        "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,"
            + "LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED\n"
            + "shortener,com.acme.shortener.qr,QrController,0,0,0,0,10,90,0,0,0,0\n"
            + "shortener,com.acme.shortener.config,AppConfig,0,0,0,0,50,0,0,0,0,0\n");
    System.out.println("[INFO] BUILD SUCCESS");
  }
}
