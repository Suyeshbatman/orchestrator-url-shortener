package com.acme.orchestrator.agents.sdlc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads what a Maven build left behind: Surefire and Failsafe XML reports and JaCoCo's CSV. XML
 * parsing is locked down (no DTDs, no external entities): the files come from code the agents
 * wrote.
 */
final class BuildReports {

  record Failure(String test, String message) {}

  record Tests(int total, int failed, int errors, int skipped, List<Failure> failures) {
    static final Tests NONE = new Tests(0, 0, 0, 0, List.of());
  }

  static final int MAX_FAILURES = 50;
  static final List<String> REPORT_DIRS =
      List.of("target/surefire-reports", "target/failsafe-reports");
  static final String JACOCO_CSV = "target/site/jacoco/jacoco.csv";

  private BuildReports() {}

  static Tests tests(Path module) {
    int total = 0;
    int failed = 0;
    int errors = 0;
    int skipped = 0;
    List<Failure> failures = new ArrayList<>();
    for (String dir : REPORT_DIRS) {
      Path reports = module.resolve(dir);
      if (!Files.isDirectory(reports)) {
        continue;
      }
      try (Stream<Path> files = Files.list(reports)) {
        for (Path xml :
            files
                .filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml"))
                .sorted()
                .toList()) {
          Element suite = parse(xml).getDocumentElement();
          total += intAttr(suite, "tests");
          failed += intAttr(suite, "failures");
          errors += intAttr(suite, "errors");
          skipped += intAttr(suite, "skipped");
          NodeList cases = suite.getElementsByTagName("testcase");
          for (int i = 0; i < cases.getLength() && failures.size() < MAX_FAILURES; i++) {
            Element testcase = (Element) cases.item(i);
            for (String kind : List.of("failure", "error")) {
              NodeList problems = testcase.getElementsByTagName(kind);
              if (problems.getLength() > 0) {
                Element problem = (Element) problems.item(0);
                String message = problem.getAttribute("message");
                failures.add(
                    new Failure(
                        testcase.getAttribute("classname") + "." + testcase.getAttribute("name"),
                        message.isBlank() ? problem.getAttribute("type") : message));
              }
            }
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException("cannot read test reports in " + reports, e);
      }
    }
    return new Tests(total, failed, errors, skipped, List.copyOf(failures));
  }

  /**
   * Line coverage, 0–1, from JaCoCo's CSV ({@code
   * GROUP,PACKAGE,CLASS,…,LINE_MISSED,LINE_COVERED,…}); 0 when there is no report. Packages in
   * {@code excluded} (and below) don't count.
   */
  static double lineCoverage(Path module, Set<String> excluded) {
    Path csv = module.resolve(JACOCO_CSV);
    if (!Files.exists(csv)) {
      return 0;
    }
    long missed = 0;
    long covered = 0;
    try {
      List<String> lines = Files.readAllLines(csv);
      for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
        String[] cols = line.split(",");
        if (cols.length < 9) {
          continue;
        }
        String pkg = cols[1];
        if (excluded.stream().anyMatch(x -> pkg.equals(x) || pkg.startsWith(x + "."))) {
          continue;
        }
        missed += Long.parseLong(cols[7].strip());
        covered += Long.parseLong(cols[8].strip());
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + csv, e);
    } catch (NumberFormatException e) {
      throw new IllegalStateException(csv + " is not a JaCoCo CSV report", e);
    }
    return missed + covered == 0 ? 0 : (double) covered / (missed + covered);
  }

  private static Document parse(Path xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      return builder.parse(xml.toFile());
    } catch (Exception e) {
      throw new IllegalStateException("cannot parse test report " + xml.getFileName(), e);
    }
  }

  private static int intAttr(Element e, String name) {
    String value = e.getAttribute(name);
    return value.isBlank() ? 0 : (int) Double.parseDouble(value);
  }
}
