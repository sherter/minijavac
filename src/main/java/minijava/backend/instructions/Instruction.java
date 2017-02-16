package minijava.backend.instructions;

import static org.jooq.lambda.Seq.seq;

import com.google.common.base.Preconditions;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import minijava.backend.block.PhiFunction;
import minijava.backend.operands.ImmediateOperand;
import minijava.backend.operands.Operand;
import minijava.backend.operands.OperandWidth;
import minijava.backend.operands.RegisterOperand;
import minijava.backend.operands.Use;
import minijava.backend.registers.Register;
import org.jooq.lambda.Seq;

public abstract class Instruction {
  // We need to separate input from output operands in order to differentiate uses from defs.
  // Mostly input operands subsume output operands but there are cases such as Mov, where
  // the RHS is not an input if it's a RegisterOperand.
  // We need to store output operands as operands rather than registers, because otherwise we would
  // not have the information needed for lowering.
  private final List<Operand> inputs;
  private final List<Operand> outputs;
  private final Set<Register> hints = new HashSet<>();
  private final Set<Operand> mayBeMemory = new HashSet<>();

  protected Instruction(List<Operand> inputs, List<Operand> outputs) {
    Preconditions.checkArgument(!inputs.contains(null), "null input operand");
    Preconditions.checkArgument(!outputs.contains(null), "null output register");
    Preconditions.checkArgument(
        seq(outputs).ofType(ImmediateOperand.class).isEmpty(), "Can't output into an immediate");
    this.inputs = inputs;
    this.outputs = outputs;
  }

  protected void setHints(Operand... shouldBeAssignedTheSameRegister) {
    setHints(Seq.of(shouldBeAssignedTheSameRegister));
  }

  protected void setHints(Iterable<Operand> shouldBeAssignedTheSameRegister) {
    for (RegisterOperand operand :
        seq(shouldBeAssignedTheSameRegister).ofType(RegisterOperand.class)) {
      assert inputs.contains(operand) || outputs.contains(operand)
          : "Can only hint connections between input and output operands";
      hints.add(operand.register);
    }
  }

  protected void setMayBeMemory(Operand which) {
    mayBeMemory.add(which);
  }

  public Set<Use> uses() {
    Set<Use> uses = new HashSet<>();
    for (Operand input : inputs) {
      uses.addAll(input.reads(false, mayBeMemory.contains(input)));
    }
    for (Operand output : outputs) {
      uses.addAll(output.reads(true, mayBeMemory.contains(output)));
    }
    return uses;
  }

  public Set<Use> defs() {
    return seq(outputs)
        .ofType(RegisterOperand.class)
        .map(def -> new Use(def.register, mayBeMemory.contains(def)))
        .toSet();
  }

  public abstract void accept(Visitor visitor);

  protected static List<Operand> toOperands(Iterable<? extends Register> registers) {
    return toOperands(OperandWidth.Quad, registers);
  }

  protected static List<Operand> toOperands(
      OperandWidth width, Iterable<? extends Register> registers) {
    return seq(registers).map(reg -> (Operand) new RegisterOperand(width, reg)).toList();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(getClass().getSimpleName());
    sb.append('(');
    sb.append(String.join(", ", seq(inputs).map(Object::toString)));
    sb.append(')');
    if (outputs.size() > 0) {
      sb.append(" -> ");
      sb.append(String.join(", ", seq(outputs).map(Object::toString)));
    }
    return sb.toString();
  }

  public Set<Register> registerHints() {
    return new HashSet<>(hints);
  }

  public interface Visitor extends CodeBlockInstruction.Visitor {

    default void visit(Jcc jcc) {}

    default void visit(Jmp jmp) {}

    default void visit(Label label) {}

    default void visit(Pop pop) {}

    default void visit(Push push) {}

    default void visit(Ret ret) {}

    default void visit(PhiFunction phi) {}

    default void visit(Xchg xchg) {}
  }
}