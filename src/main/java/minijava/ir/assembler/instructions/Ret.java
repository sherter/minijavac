package minijava.ir.assembler.instructions;

import com.google.common.collect.ImmutableList;
import java.util.List;
import minijava.ir.assembler.location.Register;

/** <code>ret</code> instruction that returns from a function call */
public class Ret extends Instruction {
  public Ret() {
    super(Register.Width.Quad);
  }

  @Override
  public Type getType() {
    return Type.RET;
  }

  @Override
  public List<Argument> getArguments() {
    return ImmutableList.of();
  }

  @Override
  public <T> T accept(InstructionVisitor<T> visitor) {
    return visitor.visit(this);
  }
}
