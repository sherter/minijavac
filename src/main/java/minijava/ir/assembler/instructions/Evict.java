package minijava.ir.assembler.instructions;

import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import minijava.ir.assembler.operands.Operand;
import minijava.ir.assembler.operands.OperandWidth;
import minijava.ir.assembler.registers.AMD64Register;

/**
 * A meta instruction to force the temporary eviction of some registers. The registers may only be
 * reused by an allocator in instructions using NodeLocations
 */
public class Evict extends Instruction {

  public final List<AMD64Register> registers;

  public Evict(List<AMD64Register> registers) {
    super(OperandWidth.Quad);
    this.registers = Collections.unmodifiableList(registers);
  }

  public Evict(AMD64Register... registers) {
    this(Arrays.asList(registers));
  }

  @Override
  public Type getType() {
    return Type.EVICT;
  }

  @Override
  public List<Operand> getArguments() {
    return ImmutableList.of();
  }

  @Override
  public <T> T accept(InstructionVisitor<T> visitor) {
    return visitor.visit(this);
  }
}
