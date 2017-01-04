package minijava.ir.assembler;

import static minijava.ir.utils.FirmUtils.getMethodLdName;

import firm.Graph;
import firm.Mode;
import firm.TargetValue;
import firm.nodes.*;
import java.util.*;
import minijava.ir.assembler.instructions.Argument;
import minijava.ir.assembler.instructions.ConstArgument;
import minijava.ir.assembler.location.Location;
import minijava.ir.assembler.location.RegRelativeLocation;
import minijava.ir.assembler.location.Register;
import minijava.ir.utils.MethodInformation;

/**
 * Simple stack based node allocator that tries to implement the scheme from the compiler lab
 * slides.
 *
 * <p>Every value is stored on the stack in a new slot thereby using lot's of memory. The advantage
 * of this inefficiency is the simplicity of the implementation.
 */
public class SimpleNodeAllocator {

  private static final int STACK_SLOT_SIZE = 8; // we're on an 64bit system
  private Map<Node, Integer> assignedStackSlots;
  private int currentSlotNumber = 1;
  private Graph graph;
  private MethodInformation info;
  private boolean useABIConformCalling = false;
  private List<RegRelativeLocation> regPassedParameterLocations;
  private Map<Integer, Register> regPassedParameterSlotsMap;

  public SimpleNodeAllocator(Graph graph, boolean useABIConformCalling) {
    this.graph = graph;
    this.info = new MethodInformation(graph);
    this.assignedStackSlots = new HashMap<>();
    this.useABIConformCalling = useABIConformCalling;
    this.regPassedParameterLocations = new ArrayList<>();
    regPassedParameterSlotsMap = new HashMap<>();
    if (useABIConformCalling) {
      Register.methodArgumentQuadRegisters.forEach(
          x -> {
            regPassedParameterLocations.add((RegRelativeLocation) createNewTemporaryVariable());
            regPassedParameterSlotsMap.put(currentSlotNumber - 1, x);
          });
    }
  }

  public Location getLocation(Node node) {
    if (node instanceof Conv) {
      // Conv nodes are only generated for div and mod instructions
      // this leads either to
      // [Mod] <- [Proj] <- [Conv]
      // where we return the location of the [Mov] node
      // or
      // [Arg node] <- [Conv] <- [Mod]
      // where we return the location of the [Arg node]
      Node operand = ((Conv) node).getOp();
      if (operand.getPredCount() > 0) {
        Node operandPred = operand.getPred(0);
        if (operandPred instanceof Mod || operandPred instanceof Div) {
          return getLocation(operandPred);
        }
      }
      return getLocation(operand);
    } else if (node instanceof Proj) {
      Proj proj = (Proj) node;
      if (proj.getPred().equals(graph.getArgs())) {
        // the proj node points to a method argument
        int slot = proj.getNum();
        if (useABIConformCalling) {
          if (slot < Register.methodArgumentQuadRegisters.size()) {
            return regPassedParameterLocations.get(slot);
          }
          slot = slot - Register.methodArgumentQuadRegisters.size();
        }
        // this seems to be correct?
        return new RegRelativeLocation(Register.BASE_POINTER, (slot + 2) * STACK_SLOT_SIZE);
      } else if (proj.getPred().getPredCount() > 0 && proj.getPred().getPred(0) instanceof Call) {
        // the proj node points to method result
        // the result always lays in the RAX register
        // [Call] <- [Tuple Proj] <- [Is Proj]
        return getStackSlotAsLocation(proj.getPred().getPred(0));
      } else if (node.getPred(0) instanceof Load || node.getPred(0) instanceof Store) {
        // [Proj p64] <- [Load|Store] <- [Proj res]
        return getLocation(node.getPred(0));
      }
    }
    return getStackSlotAsLocation(node);
  }

  public List<Argument> getArguments(Node node) {
    List<Argument> args = new ArrayList<>();
    int start = 0;
    if (node instanceof Call) {
      start = 2;
    } else if (node instanceof Return || node instanceof Mod || node instanceof Div) {
      // they have memory dependencies
      start = 1;
    }
    for (int i = start; i < node.getPredCount(); i++) {
      args.add(getAsArgument(node.getPred(i)));
    }
    return args;
  }

  public Argument getAsArgument(Node node) {
    if (node instanceof Const) {
      TargetValue tarVal = ((Const) node).getTarval();
      if (tarVal.isLong()) {
        return new ConstArgument(tarVal.asInt());
      } else if (tarVal.getMode().equals(Mode.getb())) {
        return new ConstArgument(tarVal.isNull() ? 0 : 1);
      } else {
        assert false;
      }
    }
    return getLocation(node);
  }

  public Location getResultLocation(Node node) {
    return getLocation(node);
  }

  public int getActivationRecordSize() {
    return (currentSlotNumber + 1) * STACK_SLOT_SIZE;
  }

  private int getStackSlotOffset(Node node) {
    return assignedStackSlots.get(node) * STACK_SLOT_SIZE;
  }

  private void assignStackSlot(Node node) {
    assignedStackSlots.put(node, currentSlotNumber++);
  }

  private Location getStackSlotAsLocation(Node node) {
    if (!assignedStackSlots.containsKey(node)) {
      assignStackSlot(node);
    }
    return new RegRelativeLocation(Register.BASE_POINTER, -getStackSlotOffset(node));
  }

  public String getActivationRecordInfo() {
    List<String> lines = new ArrayList<>();
    Map<Integer, Node> nodesForAssignedSlot = new HashMap<>();
    for (Node node : assignedStackSlots.keySet()) {
      nodesForAssignedSlot.put(assignedStackSlots.get(node), node);
    }
    int argStart = useABIConformCalling ? Register.methodArgumentQuadRegisters.size() : 0;
    for (int i = info.paramNumber - 1; i >= argStart; i--) {
      String slotInfo = String.format("%3d[%3d(%%esp)]", i + 2, (i + 2) * STACK_SLOT_SIZE);
      if (i == 0) {
        lines.add(slotInfo + ": this");
      } else {
        lines.add(slotInfo + ": argument " + (i - 1));
      }
    }
    if (useABIConformCalling) {
      for (int i = Math.min(info.paramNumber, Register.methodArgumentQuadRegisters.size()) - 1;
          i >= 0;
          i--) {
        Register reg = Register.methodArgumentQuadRegisters.get(i);
        String slotInfo =
            String.format(
                "%%%s/%%%s      ", Register.getLongVersion(reg).registerName, reg.registerName);
        if (i == 0) {
          lines.add(slotInfo + ": this");
        } else {
          lines.add(slotInfo + ": argument " + (i - 1));
        }
      }
    }
    for (int slot = 0; slot < currentSlotNumber; slot++) {
      String slotInfo = String.format("%3d[%3d(%%ebp)]", slot, -slot * STACK_SLOT_SIZE);
      if (slot == 0) {
        slotInfo += ": backed up base pointer";
      } else if (regPassedParameterSlotsMap.containsKey(slot)) {
        slotInfo +=
            String.format(": Backuped %s", regPassedParameterSlotsMap.get(slot).registerName);
      } else if (nodesForAssignedSlot.containsKey(slot)) {
        slotInfo += ": " + getInfoStringForNode(nodesForAssignedSlot.get(slot));
      }
      lines.add(slotInfo);
    }
    return String.join(System.lineSeparator(), lines);
  }

  private String getInfoStringForNode(Node node) {
    if (node instanceof Call) {
      return node.toString() + " -> " + getMethodLdName((Call) node);
    }
    return node.toString();
  }

  public Location createNewTemporaryVariable() {
    return new RegRelativeLocation(Register.BASE_POINTER, currentSlotNumber++ * -STACK_SLOT_SIZE);
  }

  public Location getRegPassedParameterLocation(int parameterNr) {
    return regPassedParameterLocations.get(parameterNr);
  }
}