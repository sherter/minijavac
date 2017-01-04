package minijava.ir.assembler.instructions;

import minijava.ir.assembler.location.Location;
import minijava.ir.assembler.location.Register;
import org.jooq.lambda.tuple.Tuple2;

/** Moves the source value into the destination */
public class Mov extends Instruction {

  public final Argument source;
  public final Location destination;

  public Mov(Argument source, Location destination) {
    Tuple2<Argument, Argument> t = BinaryInstruction.getAdjustedRegisters(source, destination);
    this.source = t.v1;
    this.destination = (Location) t.v2;
  }

  @Override
  protected String toGNUAssemblerWoComments() {
    return createGNUAssemblerWoComments(source, destination);
  }

  @Override
  public Type getType() {
    return Type.MOV;
  }

  @Override
  protected Register.Width getWidthOfArguments() {
    return getMaxWithOfArguments(source, destination);
  }
}