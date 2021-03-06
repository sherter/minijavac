package minijava.ir.assembler.instructions;

public class Add extends BinaryInstruction {
  public Add(Argument left, Argument right) {
    super(left, right);
  }

  @Override
  public Type getType() {
    return Type.ADD;
  }

  @Override
  public <T> T accept(InstructionVisitor<T> visitor) {
    return visitor.visit(this);
  }
}
