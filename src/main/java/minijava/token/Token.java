package minijava.token;

import static com.google.common.base.Preconditions.checkNotNull;
import static minijava.token.Terminal.*;

import java.util.Arrays;
import minijava.util.SourceCodeReferable;
import minijava.util.SourceRange;
import org.jetbrains.annotations.Nullable;

/** Instances of this class are immutable. */
public class Token implements SourceCodeReferable {

  public final Terminal terminal;
  private final SourceRange range;
  @Nullable public final String lexval;

  public Token(Terminal terminal, SourceRange range, @Nullable String lexval) {
    this.range = range;
    this.terminal = checkNotNull(terminal);
    if (!isOneOf(IDENT, INTEGER_LITERAL, RESERVED) && lexval != null) {
      throw new IllegalArgumentException(
          "lexval may only be set for identifiers, integer literals or the reserved terminals");
    }
    this.lexval = lexval == null ? null : lexval.intern();
  }

  public boolean isOperator() {
    return terminal.associativity != null;
  }

  public Associativity associativity() {
    if (terminal.associativity == null) {
      throw new UnsupportedOperationException(terminal + " has no associativity");
    }
    return terminal.associativity;
  }

  public int precedence() {
    if (terminal.precedence == null) {
      throw new UnsupportedOperationException(terminal + " has no precedence");
    }
    return terminal.precedence;
  }

  public boolean isOneOf(Terminal... terminals) {
    return Arrays.stream(terminals).anyMatch(t -> t == this.terminal);
  }

  @Override
  public String toString() {
    switch (terminal) {
      case IDENT:
        return "identifier " + lexval;
      case INTEGER_LITERAL:
        return "integer literal " + lexval;
      case RESERVED:
        return lexval;
      case EOF:
        return "EOF";
      default:
        return terminal.string;
    }
  }

  @Override
  public SourceRange range() {
    return range;
  }
}
