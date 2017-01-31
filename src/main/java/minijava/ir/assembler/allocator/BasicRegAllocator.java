package minijava.ir.assembler.allocator;

import com.google.common.collect.ImmutableList;
import java.util.*;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import minijava.ir.assembler.NodeAllocator;
import minijava.ir.assembler.block.LinearCodeSegment;
import minijava.ir.assembler.instructions.*;
import minijava.ir.assembler.operands.ImmediateOperand;
import minijava.ir.assembler.operands.MemoryOperand;
import minijava.ir.assembler.operands.Operand;
import minijava.ir.assembler.operands.OperandWidth;
import minijava.ir.assembler.operands.Parameter;
import minijava.ir.assembler.operands.StackSlot;
import minijava.ir.assembler.registers.*;

/**
 * This is a basic implementation of a register allocator, that allocates {@link VirtualRegister}
 * instances in registers and the heap.
 *
 * <p>It's pretty slow (stores everything on the stack) but works well.
 */
public class BasicRegAllocator extends AbstractRegAllocator
    implements InstructionVisitor<List<Instruction>> {

  private Map<Operand, StackSlot> argumentStackSlots;
  private Map<StackSlot, Operand> usedStackSlots;
  /**
   * Empty optionals are equivalent to "there was a stack slot assigned here and was later removed,
   * but there are stack slots that have a bigger offset)
   */
  private List<Optional<StackSlot>> assignedStackSlots;

  private Map<Operand, AMD64Register> argumentsInRegisters;
  private int maxStackDepth;
  private int currentStackDepth;
  private Instruction currentInstruction;

  public BasicRegAllocator(LinearCodeSegment code, NodeAllocator nodeAllocator) {
    super(code, nodeAllocator);
    this.maxStackDepth = 0;
    this.currentStackDepth = 0;
    this.assignedStackSlots = new ArrayList<>();
    this.argumentStackSlots = new HashMap<>();
    this.usedStackSlots = new HashMap<>();
    this.argumentsInRegisters = new HashMap<>();
    // put a pseudo element on the stack as we can't use the 0. stack slot (we backup the old frame pointer there)
    putArgumentOnStack(new ImmediateOperand(OperandWidth.Quad, 42));
  }

  private List<Instruction> prepopulateStack() {
    List<Instruction> instructions = new ArrayList<>();
    // we populate the stack with all NodeArguments used in this method
    // this will result in a pretty large stack at the advantage of being simple
    // we first populate the stack with the parameters
    // then we copy the parameters from the registers
    for (int i = 0;
        i
            < Math.min(
                AMD64Register.methodArgumentQuadRegisters.size(), nodeAllocator.parameters.size());
        i++) {
      Parameter param = nodeAllocator.parameters.get(i);
      putArgumentOnStack(param);
      AMD64Register paramReg =
          AMD64Register.methodArgumentQuadRegisters.get(i).ofWidth(param.width);
      instructions.add(
          new Mov(paramReg, (Register) getCurrentLocation(param))
              .com(String.format("Copy %d. parameter on the stack", i)));
    }
    // we assign all NodeLocations that appear in instructions a stack slot
    for (LinearCodeSegment.InstructionOrString instructionOrString : code) {
      if (instructionOrString.instruction.isPresent()) {
        for (Operand arg : instructionOrString.instruction.get().getArguments()) {
          if (arg instanceof VirtualRegister && !(arg instanceof Parameter)) {
            putArgumentOnStack(arg);
          }
        }
      }
    }
    return instructions;
  }

  private void putArgumentOnStack(Operand operand) {
    if (!hasStackSlotAssigned(operand)) {
      currentStackDepth += 8; //operand.width.sizeInBytes;
      StackSlot slot = new StackSlot(operand.width, -currentStackDepth);
      slot.setComment(operand.getComment());
      argumentStackSlots.put(operand, slot);
      usedStackSlots.put(slot, operand);
      assignedStackSlots.add(Optional.of(slot));
      if (currentStackDepth > maxStackDepth) {
        maxStackDepth = currentStackDepth;
      }
    }
  }

  public LinearCodeSegment process() {
    List<LinearCodeSegment.InstructionOrString> ret = new ArrayList<>();
    for (LinearCodeSegment.InstructionOrString instructionOrString : code) {
      if (instructionOrString.instruction.isPresent()) {
        if (currentInstruction instanceof MethodPrologue) {
          prepopulateStack()
              .stream()
              .map(LinearCodeSegment.InstructionOrString::new)
              .forEach(ret::add);
        }
        Instruction instruction = instructionOrString.instruction.get();
        currentInstruction = instruction;
        List<Instruction> replacement = instruction.accept(this);
        replacement.stream().map(LinearCodeSegment.InstructionOrString::new).forEach(ret::add);
      } else {
        ret.add(instructionOrString);
      }
    }
    for (int i = 0; i < ret.size(); i++) {
      if (ret.get(i).instruction.isPresent()) {
        if (ret.get(i).instruction.get() instanceof MetaMethodFrameAlloc) {
          ret.set(i, new LinearCodeSegment.InstructionOrString(new AllocStack(currentStackDepth)));
        }
      }
    }
    return new LinearCodeSegment(code.codeBlocks, ret, code.getComments());
  }

  private boolean hasStackSlotAssigned(Operand operand) {
    return argumentStackSlots.containsKey(operand);
  }

  @Override
  public List<Instruction> visit(MethodPrologue prologue) {
    return ImmutableList.of(
        new Push(AMD64Register.BASE_POINTER).com("Backup old base pointer"),
        new Mov(AMD64Register.STACK_POINTER, AMD64Register.BASE_POINTER),
        new MetaMethodFrameAlloc());
  }

  @Override
  public List<Instruction> visit(Add add) {
    return visitBinaryInstruction(add, Add::new);
  }

  private List<Instruction> visitBinaryInstruction(
      BinaryInstruction binop, BiFunction<Operand, AMD64Register, Instruction> instrCreator) {
    return visitBinaryInstruction(binop, binop.left, binop.right, instrCreator);
  }

  private List<Instruction> visitBinaryInstruction(
      Instruction instruction,
      Operand left,
      Operand right,
      BiFunction<Operand, AMD64Register, Instruction> instrCreator) {
    List<Instruction> instructions = new ArrayList<>();
    // we copy both arguments simply into these registers
    // they are new in the x86-64 (compared to x86) and can therefore be used freely
    AMD64Register leftReg;
    if (!(left instanceof AMD64Register)) {
      leftReg = AMD64Register.R14.ofWidth(left.width);
      instructions.add(new Mov(getCurrentLocation(left), leftReg));
    } else {
      leftReg = (AMD64Register) left;
    }
    AMD64Register rightReg;
    if (!(right instanceof AMD64Register)) {
      rightReg = AMD64Register.R15.ofWidth(right.width);
      instructions.add(new Mov(getCurrentLocation(right), rightReg));
    } else {
      rightReg = (AMD64Register) right;
    }
    instructions.add(instrCreator.apply(leftReg, rightReg).firmAndComments(instruction));
    // copy the result back
    if (!(right instanceof ImmediateOperand)) {
      if (!(right instanceof AMD64Register)) {
        instructions.add(new Mov(rightReg, (Register) getCurrentLocation(right)));
      }
    }
    return instructions;
  }

  private List<Instruction> visitUnaryInstruction(
      Instruction instruction, Operand arg, Function<AMD64Register, Instruction> instrCreator) {
    List<Instruction> instructions = new ArrayList<>();
    // use a temporary register
    AMD64Register reg = AMD64Register.R14.ofWidth(arg.width);
    if (arg instanceof AMD64Register) {
      reg = (AMD64Register) arg;
    } else {
      instructions.add(new Mov(getCurrentLocation(arg), reg));
    }
    instructions.add(instrCreator.apply(reg).firmAndComments(instruction));
    if (!(arg instanceof ImmediateOperand) && !(arg instanceof AMD64Register)) {
      instructions.add(new Mov(reg, (Register) getCurrentLocation(arg)));
    }
    return instructions;
  }

  @Override
  public List<Instruction> visit(AllocStack allocStack) {
    throw new RuntimeException();
  }

  @Override
  public List<Instruction> visit(And and) {
    return visitBinaryInstruction(and, And::new);
  }

  @Override
  public List<Instruction> visit(Call call) {
    throw new RuntimeException();
  }

  @Override
  public List<Instruction> visit(CLTD cltd) {
    return ImmutableList.of(cltd);
  }

  @Override
  public List<Instruction> visit(Cmp cmp) {
    return visitBinaryInstruction(cmp, Cmp::new);
  }

  @Override
  public List<Instruction> visit(ConditionalJmp jmp) {
    return ImmutableList.of(jmp);
  }

  @Override
  public List<Instruction> visit(DeallocStack deallocStack) {
    throw new RuntimeException();
  }

  @Override
  public List<Instruction> visit(Div div) {
    return ImmutableList.of(div);
  }

  @Override
  public List<Instruction> visit(Evict evict) {
    return ImmutableList.of();
  }

  @Override
  public List<Instruction> visit(Jmp jmp) {
    return ImmutableList.of(jmp);
  }

  /** Returns the current registers of the argument and doesn't generate any code. */
  private Operand getCurrentLocation(Operand arg) {
    if (arg instanceof VirtualRegister) {
      if (argumentsInRegisters.containsKey(arg)) {
        return argumentsInRegisters.get(arg);
      } else {
        if (arg instanceof Parameter) {
          if (((Parameter) arg).paramNumber >= AMD64Register.methodArgumentQuadRegisters.size()) {
            int inRegs = AMD64Register.methodArgumentQuadRegisters.size();
            int paramNum = ((Parameter) arg).paramNumber;
            return new StackSlot(arg.width, (paramNum - inRegs + 2) * 8);
          }
        }
        return argumentStackSlots.get(arg);
      }
    }
    // we don't have to care about "real" locations here
    return arg;
  }

  @Override
  public List<Instruction> visit(MetaCall metaCall) {
    List<AMD64Register> argRegs = AMD64Register.methodArgumentQuadRegisters;
    List<Instruction> instructions = new ArrayList<>();
    // move the register passed argument into the registers
    for (int i = 0; i < Math.min(argRegs.size(), metaCall.methodInfo.paramNumber); i++) {
      Operand arg = metaCall.args.get(i);
      if (getCurrentLocation(arg) == null) {
        throw new NullPointerException(
            String.format("%s: Operand %s doesn't exist", metaCall, arg));
      }
      instructions.add(
          new Mov(getCurrentLocation(arg), argRegs.get(i).ofWidth(arg.width))
              .com(String.format("Move %d.th param into register", i)));
    }
    // the 64 ABI requires the stack to aligned to 16 bytes
    instructions.add(new Push(AMD64Register.STACK_POINTER).com("Save old stack pointer"));
    int stackAlignmentDecrement = 0;
    int stackDepthWithProlog = currentStackDepth + 8; // for saving %rbp
    if (stackDepthWithProlog % 16 != 0) { // the stack isn't aligned to 16 Bytes
      // we have to align the stack by decrementing the stack counter
      // this works because we can assume that the base pointer is correctly aligned
      // (induction and the main method is correctly called)
      stackAlignmentDecrement = 16 - stackDepthWithProlog % 16;
      instructions.add(
          new Add(
                  new ImmediateOperand(OperandWidth.Quad, -stackAlignmentDecrement),
                  AMD64Register.STACK_POINTER)
              .com("Decrement the stack to ensure alignment"));
    }
    // Use the tmpReg to move the additional parameters on the stack
    for (int i = metaCall.methodInfo.paramNumber - 1; i >= argRegs.size(); i--) {
      Operand arg = metaCall.args.get(i);
      instructions.add(new Push(getCurrentLocation(arg)));
    }
    instructions.add(
        new Call(
                metaCall.methodInfo.hasReturnValue
                    ? metaCall.result.get().width
                    : OperandWidth.Quad,
                metaCall.methodInfo.ldName)
            .com("Call the function")
            .firm(metaCall.firm()));
    instructions.add(
        new DeallocStack(
            Math.max(
                    0,
                    metaCall.methodInfo.paramNumber
                        - AMD64Register.methodArgumentQuadRegisters.size())
                * 8));
    if (stackAlignmentDecrement != 0) { // we aligned the stack explicitly before
      instructions.add(
          new Add(
                  new ImmediateOperand(OperandWidth.Quad, stackAlignmentDecrement),
                  AMD64Register.STACK_POINTER)
              .com("Remove stack alignment"));
    }
    instructions.add(
        new Mov(
                new MemoryOperand(OperandWidth.Quad, AMD64Register.STACK_POINTER, 0),
                AMD64Register.STACK_POINTER)
            .com("Restore old stack pointer"));
    if (metaCall.methodInfo.hasReturnValue) {
      Register ret = (Register) getCurrentLocation(metaCall.result.get());
      // now we move the return value from the %rax register into the return value's registers
      instructions.addAll(visit(new Mov(AMD64Register.RETURN_REGISTER.ofWidth(ret.width), ret)));
    }
    return instructions;
  }

  @Override
  public List<Instruction> visit(Mov mov) {
    return visitBinaryInstruction(mov, mov.source, mov.destination, Mov::new);
  }

  @Override
  public List<Instruction> visit(MovFromSmallerToGreater mov) {
    //mov.com("bla");
    return visitBinaryInstruction(
        mov,
        mov.source,
        mov.destination,
        (l, r) -> new MovFromSmallerToGreater(l, r.ofWidth(mov.source.width)));
  }

  @Override
  public List<Instruction> visit(Mul mul) {
    return visitBinaryInstruction(mul, Mul::new);
  }

  @Override
  public List<Instruction> visit(Neg neg) {
    return visitUnaryInstruction(neg, neg.arg, Neg::new);
  }

  @Override
  public List<Instruction> visit(Pop pop) {
    return visitUnaryInstruction(pop, pop.arg, Pop::new);
  }

  @Override
  public List<Instruction> visit(Push push) {
    return visitUnaryInstruction(push, push.arg, Push::new);
  }

  @Override
  public List<Instruction> visit(Ret ret) {
    return ImmutableList.of(ret);
  }

  @Override
  public List<Instruction> visit(final minijava.ir.assembler.instructions.Set set) {
    return visitUnaryInstruction(
        set, set.arg, arg -> new minijava.ir.assembler.instructions.Set(set.relation, arg));
  }

  @Override
  public List<Instruction> visit(Sub sub) {
    return visitBinaryInstruction(sub, (l, r) -> new Sub(l, r));
  }

  @Override
  public List<Instruction> visit(DisableRegisterUsage disableRegisterUsage) {
    return ImmutableList.of();
  }

  @Override
  public List<Instruction> visit(EnableRegisterUsage enableRegisterUsage) {
    return ImmutableList.of();
  }
}
