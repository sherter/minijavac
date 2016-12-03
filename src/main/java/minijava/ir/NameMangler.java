package minijava.ir;

import com.sun.jna.Platform;
import java.util.Arrays;
import java.util.stream.Collectors;

public class NameMangler {

  private static final String SEP = "_";

  public static String mangleClassName(String className) {
    return SEP + SEP + replaceSep(className);
  }

  public static String mangleMethodName(String className, String methodName) {
    return combineWithSep(mangleClassName(className), "M", replaceSep(methodName));
  }

  public static String mangleInstanceFieldName(String className, String fieldName) {
    return combineWithSep(mangleClassName(className), "I", replaceSep(fieldName));
  }

  public static String mangledMainMethodName() {
    if (Platform.isMac() || Platform.isWindows()) {
      return "_mjMain";
    }
    return "mjMain";
  }

  public static String mangledPrintIntMethodName() {
    if (Platform.isMac() || Platform.isWindows()) {
      return "_print_int";
    }
    return "print_int";
  }

  public static String mangledCallocMethodName() {
    if (Platform.isMac() || Platform.isWindows()) {
      return "_calloc_impl";
    }
    return "calloc_impl";
  }

  private static String replaceSep(String name) {
    return name.replace(SEP, SEP + SEP);
  }

  private static String combineWithSep(String... names) {
    return Arrays.stream(names).collect(Collectors.joining(SEP));
  }
}