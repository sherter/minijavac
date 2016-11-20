package minijava.semantic;

import static org.jooq.lambda.tuple.Tuple.tuple;

import com.google.common.primitives.Ints;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import minijava.ast.*;
import minijava.ast.Class;
import minijava.ast.Expression.BooleanLiteral;
import minijava.ast.Expression.IntegerLiteral;
import minijava.ast.Expression.NewArray;
import minijava.ast.Expression.NewObject;
import minijava.ast.Method.Parameter;
import minijava.ast.Statement.ExpressionStatement;
import minijava.util.SourceRange;
import org.jetbrains.annotations.NotNull;
import org.jooq.lambda.tuple.Tuple2;

public class NameAnalyzer
    implements Program.Visitor<Nameable, Program<Ref>>,
        Class.Visitor<Nameable, Class<Ref>>,
        Field.Visitor<Nameable, Field<Ref>>,
        Type.Visitor<Nameable, Type<Ref>>,
        Method.Visitor<Nameable, Method<Ref>>,
        BlockStatement.Visitor<Nameable, BlockStatement<Ref>>,
        Expression.Visitor<Nameable, Tuple2<Expression<Ref>, Type<Ref>>> {

  private static final Expression<Nameable> THIS_EXPR =
      Expression.ReferenceTypeLiteral.this_(SourceRange.FIRST_CHAR);
  // contains BasicType and Class<? extends Nameable> Definitions
  // TODO: make them both derive from a common interface type
  private SymbolTable<Definition> types = new SymbolTable<>();

  // TODO: make both BlockStatement.Variable and Parameter derive from a common interface type
  private SymbolTable<Definition> locals = new SymbolTable<>();
  private SymbolTable<Field<? extends Nameable>> fields = new SymbolTable<>();
  private SymbolTable<Method<? extends Nameable>> methods = new SymbolTable<>();
  private Type<Ref> currentClass;
  private Method<? extends Nameable> currentMethod;
  private Method<? extends Nameable> mainMethod;
  private boolean hasReturned;

  @Override
  public Program<Ref> visitProgram(Program<? extends Nameable> that) {
    mainMethod = null;
    // collect all types first (throws if duplicates exist)
    this.types = that.acceptVisitor(new TypeCollector());
    List<Class<Ref>> refClasses = new ArrayList<>(that.declarations.size());
    for (Class<? extends Nameable> c : that.declarations) {
      Class<Ref> refClass = c.acceptVisitor(this);
      refClasses.add(refClass);
    }
    if (mainMethod == null) {
      throw new SemanticError(that.range(), "No main method defined");
    }
    return new Program<>(refClasses, that.range());
  }

  @Override
  public Class<Ref> visitClassDeclaration(Class<? extends Nameable> that) {
    currentClass = new Type<>(new Ref(that), 0, that.range());
    // fields in current class
    fields = new SymbolTable<>();
    fields.enterScope();
    List<Field<Ref>> newFields = new ArrayList<>(that.fields.size());
    for (Field<? extends Nameable> f : that.fields) {
      if (fields.inCurrentScope(f.name())) {
        throw new SemanticError(that.range(), "Field is already defined in this scope");
      }
      fields.insert(f.name(), f);
      Field<Ref> field = f.acceptVisitor(this);
      newFields.add(field);
    }

    // methods in current class
    methods = new SymbolTable<>();
    methods.enterScope();

    // First pick up all method declarations
    for (Method<? extends Nameable> m : that.methods) {
      if (methods.inCurrentScope(m.name())) {
        throw new SemanticError(that.range(), "Method is already defined in this scope");
      }
      methods.insert(m.name(), m);
    }

    List<Method<Ref>> newMethods = new ArrayList<>(that.methods.size());
    for (Method<? extends Nameable> m : that.methods) {
      currentMethod = m;
      Method<Ref> method = m.acceptVisitor(this);
      newMethods.add(method);
    }

    return new Class<>(that.name(), newFields, newMethods, that.range());
  }

  @Override
  public Field<Ref> visitField(Field<? extends Nameable> that) {
    Type<Ref> type = that.type.acceptVisitor(this);
    checkElementTypeIsNotVoid(type);
    return new Field<>(type, that.name(), that.range(), currentClass);
  }

  @Override
  public Type<Ref> visitType(Type<? extends Nameable> that) {
    String typeName = that.typeRef.name();
    Optional<Definition> optDef = types.lookup(typeName);
    if (!optDef.isPresent()) {
      throw new SemanticError(that.range(), "Type " + typeName + " is not defined");
    }
    return new Type<>(new Ref(optDef.get()), that.dimension, that.range());
  }

  @Override
  public Method<Ref> visitMethod(Method<? extends Nameable> that) {
    Type<Ref> returnType = that.returnType.acceptVisitor(this);
    // check types and transform parameters
    List<Parameter<Ref>> newParams = new ArrayList<>(that.parameters.size());
    // main gets special treatment. we ignore its parameter completely.
    if (that.isStatic) {
      // handle this quite specially
      if (!that.name().equals("main")) {
        throw new SemanticError(that.range(), "Static methods must be named main.");
      }
      if (that.parameters.size() != 1) {
        throw new SemanticError(that.range(), "The main method must have exactly one parameter.");
      }
      Parameter<? extends Nameable> p = that.parameters.get(0);
      Type<? extends Nameable> type = p.type;
      if (!type.typeRef.name().equals("String") || type.dimension != 1) {
        throw new SemanticError(
            that.range(), "The main method's parameter must have type String[].");
      }
      // This should also have been caught by the parser
      checkType(Type.VOID, returnType);

      if (mainMethod != null) {
        throw new SemanticError(
            that.range(), "There is already a main method defined at " + mainMethod.range());
      }
      mainMethod = that;
      newParams.add(
          new Parameter<>(
              Type.VOID, p.name, p.range())); // A hack to disallow redefinition and usage
    } else {
      for (Parameter<? extends Nameable> p : that.parameters) {
        Type<Ref> type = p.type.acceptVisitor(this);
        checkElementTypeIsNotVoid(type);
        newParams.add(new Parameter<>(type, p.name(), p.range()));
      }
    }

    // We collect local variables into a fresh symboltable
    locals = new SymbolTable<>();
    locals.enterScope();

    // check for parameters with same name
    for (Parameter<Ref> p : newParams) {
      if (locals.lookup(p.name()).isPresent()) {
        throw new SemanticError(
            that.range(), "There is already a parameter defined with this name");
      }
      locals.insert(p.name(), p);
    }

    hasReturned = false;
    Block<Ref> block = (Block<Ref>) that.body.acceptVisitor(this);
    locals.leaveScope();
    // Check if we returned on each possible path and if not, that the return type was void.
    if (!hasReturned) {
      // Check if we implicitly returned
      checkType(Type.VOID, returnType);
    }
    return new Method<>(
        that.isStatic, returnType, that.name(), newParams, block, that.range(), currentClass);
  }

  @Override
  public Block<Ref> visitBlock(Block<? extends Nameable> that) {
    locals.enterScope();
    List<BlockStatement<Ref>> newStatements = new ArrayList<>(that.statements.size());
    for (BlockStatement<? extends Nameable> s : that.statements) {
      // This also picks up local var decls into @locals@ on the go
      newStatements.add(s.acceptVisitor(this));
    }
    locals.leaveScope();
    return new Block<>(newStatements, that.range());
  }

  @Override
  public Statement<Ref> visitEmpty(Statement.Empty<? extends Nameable> that) {
    return new Statement.Empty<>(that.range());
  }

  @Override
  public Statement<Ref> visitIf(Statement.If<? extends Nameable> that) {
    Tuple2<Expression<Ref>, Type<Ref>> cond = that.condition.acceptVisitor(this);
    Expression<Ref> newCondition = cond.v1;
    checkType(Type.BOOLEAN, cond.v2);

    // Save the old returned flag. If this was true, hasReturned will be true afterwards,
    // no matter what the results of the two branches are.
    boolean oldHasReturned = hasReturned;
    hasReturned = false; // This is actually not necessary, but let's be explicit

    locals.enterScope();
    // then might be a block and therefore enters and leaves another subscope, but that's ok
    Statement<Ref> newThen = (Statement<Ref>) that.then.acceptVisitor(this);
    locals.leaveScope();

    boolean thenHasReturned = hasReturned;
    hasReturned = false;

    Statement<Ref> newElse = null;
    if (that.else_.isPresent()) {
      locals.enterScope();
      newElse = (Statement<Ref>) that.else_.get().acceptVisitor(this);
      locals.leaveScope();
    } // else hasReturned would also be false (we could always fall through the if statement)

    boolean elseHasReturned = hasReturned;

    // Either we returned before this is even executed or we returned in both branches. Otherwise we didn't return.
    hasReturned = oldHasReturned || thenHasReturned && elseHasReturned;
    return new Statement.If<>(newCondition, newThen, newElse, that.range());
  }

  @Override
  public Statement<Ref> visitExpressionStatement(ExpressionStatement<? extends Nameable> that) {
    // We don't care for the expressions type, as long as there is one
    // ... although we probably want to safe types in (Typed-)Ref later on
    Expression<Ref> expr = that.expression.acceptVisitor(this).v1;
    return new ExpressionStatement<>(expr, that.range());
  }

  @Override
  public Statement<Ref> visitWhile(Statement.While<? extends Nameable> that) {
    Tuple2<Expression<Ref>, Type<Ref>> cond = that.condition.acceptVisitor(this);
    checkType(Type.BOOLEAN, cond.v2);

    // In contrast to if, where we actually have two branches of control flow, there's no elaborate
    // hasReturned handling necessary here.
    // Suppose hasReturned was true, then it will stay true throughout the rest of the method.
    // Suppose hasReturned was false, then we won't set it to true, even if the body says so.
    // We can't always predict the value of the condition after all.
    //
    // So: just save the flag before the body and restore it after analyzing the body.

    boolean oldHasReturned = hasReturned;

    locals.enterScope();
    // if body is a block it might enter another subscope (see visitBlock) but that doesn't really matter)
    Statement<Ref> body = (Statement<Ref>) that.body.acceptVisitor(this);
    locals.leaveScope();

    hasReturned = oldHasReturned;

    return new Statement.While<>(cond.v1, body, that.range());
  }

  @Override
  public Statement<Ref> visitReturn(Statement.Return<? extends Nameable> that) {
    Type<Ref> returnType = currentMethod.returnType.acceptVisitor(this);
    if (that.expression.isPresent()) {
      if (!currentMethod.equals(mainMethod)) {
        checkElementTypeIsNotVoid(returnType);
        Tuple2<Expression<Ref>, Type<Ref>> expr = that.expression.get().acceptVisitor(this);
        checkType(returnType, expr.v2);
        hasReturned = true;
        return new Statement.Return<>(expr.v1, that.range());
      } else {
        throw new SemanticError(
            that.expression.get().range(), "Returning a value is not valid in main method");
      }
    } else {
      checkType(returnType, Type.VOID);
      hasReturned = true;
      return new Statement.Return<>(null, that.range());
    }
  }

  /**
   * This is reflexive in both arguments, so swapping them should not change the outcome of this
   * function
   */
  private void checkType(Type<Ref> expected, Type<Ref> actual) {

    SemanticError e =
        new SemanticError(
            expected.range(),
            actual.range(),
            "Expected type "
                + expected
                + "("
                + expected.range()
                + ")"
                + ", but got "
                + actual
                + "("
                + actual.range()
                + ")");

    if (expected.dimension == actual.dimension
        && expected.typeRef.name().equals(actual.typeRef.name())) {
      return;
    }

    // If any of the element types is now void, we should throw.
    if (expected.typeRef.name().equals("void") || actual.typeRef.name().equals("void")) throw e;

    // The only way this could ever work out is that either actual or expected is of type Any (type of null)
    // and the other is a reference type (every remaining type except non-array builtins).
    // Remember that actual != expected and that either dimensions or the typeRef mismatch
    if (expected == Type.ANY && (actual.dimension > 0 || actual.typeRef.def instanceof Class))
      return;
    if (actual == Type.ANY && (expected.dimension > 0 || expected.typeRef.def instanceof Class))
      return;

    throw e;
  }

  private void checkElementTypeIsNotVoid(Type<Ref> actual) {
    if (actual.typeRef.name().equals("void")) {
      throw new SemanticError(actual.range(), "Cannot use something of (element) type void");
    }
  }

  private void checkIsArrayType(Type<Ref> actual) {
    if (actual.dimension == 0) {
      throw new SemanticError(actual.range(), "Expected an array type");
    }
  }

  public BlockStatement<Ref> visitVariable(BlockStatement.Variable<? extends Nameable> that) {
    Type<Ref> variableType = that.type.acceptVisitor(this);
    checkElementTypeIsNotVoid(variableType);
    if (locals.lookup(that.name()).isPresent()) {
      throw new SemanticError(
          that.range(), "Cannot redefine " + that.name() + " at " + that.range());
    }

    Expression<Ref> rhs = null;

    if (that.rhs.isPresent()) {
      Tuple2<Expression<Ref>, Type<Ref>> ret = that.rhs.get().acceptVisitor(this);
      checkType(variableType, ret.v2);
      rhs = ret.v1;
    }

    BlockStatement.Variable<Ref> var =
        new BlockStatement.Variable<>(variableType, that.name(), rhs, that.range());
    locals.insert(that.name(), var);
    return var;
  }

  @Override
  public Tuple2<Expression<Ref>, Type<Ref>> visitBinaryOperator(
      Expression.BinaryOperator<? extends Nameable> that) {
    Tuple2<Expression<Ref>, Type<Ref>> left = that.left.acceptVisitor(this);
    Tuple2<Expression<Ref>, Type<Ref>> right = that.right.acceptVisitor(this);

    Type<Ref> resultType = null; // The result of that switch statement
    switch (that.op) {
      case ASSIGN:
        // make sure that we can actually assign something to left.v1, e.g.
        // it should be a fieldaccess or variableexpression.
        if (!(left.v1 instanceof Expression.FieldAccess)
            && !(left.v1 instanceof Expression.Variable)
            && !(left.v1 instanceof Expression.ArrayAccess)
            && !(left.v1 instanceof Expression.NewObject)) {
          throw new SemanticError(
              left.v1.range(), "Cannot assign to the expression at " + left.v1.range());
        }
        // Also left.v2 must match right.v2
        checkType(
            left.v2, right.v2); // This would be broken for type Any, but null can't be assigned to
        // The result type of the assignment expression is just left.v2
        resultType = left.v2;
        break;
      case PLUS:
      case MINUS:
      case MULTIPLY:
      case DIVIDE:
      case MODULO:
        // int -> int -> int
        // so, check that left.v1 and left.v2 is int
        checkType(Type.INT, left.v2);
        checkType(Type.INT, right.v2);
        // Then we can just reuse left's type
        resultType = left.v2;
        break;
      case OR:
      case AND:
        // bool -> bool -> bool
        checkType(Type.BOOLEAN, left.v2);
        checkType(Type.BOOLEAN, right.v2);
        resultType = left.v2;
        break;
      case EQ:
      case NEQ:
        // T -> T -> bool
        // The Ts have to match
        checkType(left.v2, right.v2);
        resultType = Type.BOOLEAN;
        break;
      case LT:
      case LEQ:
      case GT:
      case GEQ:
        // int -> int -> bool
        checkType(Type.INT, left.v2);
        checkType(Type.INT, right.v2);
        resultType = Type.BOOLEAN;
        break;
    }

    return tuple(
        new Expression.BinaryOperator<>(that.op, left.v1, right.v1, that.range()), resultType);
  }

  @Override
  public Tuple2<Expression<Ref>, Type<Ref>> visitUnaryOperator(
      Expression.UnaryOperator<? extends Nameable> that) {

    Type<Ref> expected = null;
    switch (that.op) {
      case NOT:
        // bool -> bool
        expected = Type.BOOLEAN;
        break;
      case NEGATE:
        // int -> int
        if (that.expression instanceof IntegerLiteral) {
          IntegerLiteral<Ref> lit =
              (IntegerLiteral<Ref>) that.expression; // that cast is safe, see visitIntegerLiteral
          return handleNegativeIntegerLiterals(that, lit);
        }
        expected = Type.INT;
        break;
    }

    Tuple2<Expression<Ref>, Type<Ref>> expr = that.expression.acceptVisitor(this);
    checkType(expected, expr.v2);

    return tuple(new Expression.UnaryOperator<>(that.op, expr.v1, that.range()), expr.v2);
  }

  @NotNull
  private Tuple2<Expression<Ref>, Type<Ref>> handleNegativeIntegerLiterals(
      Expression.UnaryOperator<? extends Nameable> that, IntegerLiteral<Ref> lit) {
    // handle the case of negative literals
    // This is non-compositional, because 0x80000000 is a negative number,
    // but its absolute value cannot be represented in 32 bits, thus is
    // not a valid integer literal.
    if (Ints.tryParse("-" + lit.literal) == null) {
      // insert range
      throw new SemanticError(
          lit.range(), "The literal '-" + lit.literal + "' is not a valid 32-bit number");
    }

    // Also, for the case that we got -(2147483648), which will be parsed
    // as the exact same AST, we have to reject, because this is checked
    // differently by Java.
    // Since we don't save parentheses in the AST (rightly so), we differentiate
    // by SourceRange :ugly_face:
    int minusTokenNumber = that.range().begin.tokenNumber;
    int litTokenNumber = lit.range().begin.tokenNumber;
    if (litTokenNumber > minusTokenNumber + 1 && Ints.tryParse(lit.literal) == null) {
      // MINUS INT(2147483648)
      //  ^ minusTokenNumber
      //       ^ litTokenNumber = minusTokenNumber + 1
      //
      // vs.
      //
      // MINUS LPAREN INT(2147483648) RPAREN
      //   ^ minusTokenNumber
      //              ^ litTokenNumber > minusTokenNumber + 1

      // insert range
      throw new SemanticError(
          lit.range(), "The literal '" + lit.literal + "' is not a valid 32-bit number");
    }
    // Otherwise just return what we know
    return tuple(new Expression.UnaryOperator<>(that.op, lit, that.range()), Type.INT);
  }

  @Override
  public Tuple2<Expression<Ref>, Type<Ref>> visitMethodCall(
      Expression.MethodCall<? extends Nameable> that) {

    Tuple2<Expression<Ref>, Type<Ref>> self = systemOutPrintlnHackForSelf(that);
    if (self == null) {
      // That was not a call matching System.out.println(). So we procede regularly
      self = that.self.acceptVisitor(this);
    }

    Optional<Class<Nameable>> definingClass = isTypeWithMembers(self.v2);

    if (!definingClass.isPresent()) {
      // TODO - Names
      throw new SemanticError(that.range(), "only classes have methods");
    }

    Optional<Method<Nameable>> methodOpt =
        definingClass
            .get()
            .methods
            .stream()
            .filter(m -> m.name().equals(that.method.name()))
            .findFirst();

    if (!methodOpt.isPresent()) {
      // TODO - Names
      throw new SemanticError(
          that.range(),
          "Class " + definingClass.get().name() + " has no method " + that.method.name());
    }

    Method<Nameable> m = methodOpt.get();
    m.definingClass = self.v2;

    if (m.isStatic) {
      throw new SemanticError(that.range(), "Static methods cannot be called.");
    }

    // Now that we have the method, we have access to its type.
    // Check that argument types match declared parameter types!
    if (m.parameters.size() != that.arguments.size()) {
      throw new SemanticError(
          that.range(),
          "Number of declared parameters and actual number of arguments of the call mismatched");
    }

    List<Expression<Ref>> resolvedArguments = new ArrayList<>(that.arguments.size());
    for (int i = 0; i < m.parameters.size(); ++i) {
      Type<Ref> paramType = m.parameters.get(i).type.acceptVisitor(this);
      Tuple2<Expression<Ref>, Type<Ref>> arg = that.arguments.get(i).acceptVisitor(this);
      checkType(paramType, arg.v2);
      resolvedArguments.add(arg.v1);
    }

    Type<Ref> returnType = m.returnType.acceptVisitor(this);

    return tuple(
        new Expression.MethodCall<>(self.v1, new Ref(m), resolvedArguments, that.range),
        returnType);
  }

  private Tuple2<Expression<Ref>, Type<Ref>> systemOutPrintlnHackForSelf(
      Expression.MethodCall<? extends Nameable> that) {
    if (!(that.self instanceof Expression.FieldAccess)) {
      return null;
    }
    Expression.FieldAccess<Nameable> fieldAccess = (Expression.FieldAccess<Nameable>) that.self;
    if (!(fieldAccess.self instanceof Expression.Variable)) {
      return null;
    }
    Expression.Variable<Nameable> system = (Expression.Variable) fieldAccess.self;
    if (!system.var.name().equals("System")
        || locals.lookup("System").isPresent()
        || fields.lookup("System").isPresent()
        || types.lookup("System").isPresent()) {
      return null;
    }
    // "System" is not defined in the current scope somewhere
    // We know that the expression looks like this now: System.<foo>.<methodname>()
    if (!fieldAccess.field.name().equals("out") || !that.method.name().equals("println")) {
      return null;
    }

    return tuple(Expression.ReferenceTypeLiteral.systemOut(fieldAccess.range()), Type.SYSTEM_OUT);
  }

  @NotNull
  private static Optional<Class<Nameable>> isTypeWithMembers(Type<Ref> type) {
    if (type.dimension > 0 || !(type.typeRef.def instanceof Class)) {
      return Optional.empty();
    }

    return Optional.of((Class<Nameable>) type.typeRef.def);
  }

  @Override
  public Tuple2<Expression<Ref>, Type<Ref>> visitFieldAccess(
      Expression.FieldAccess<? extends Nameable> that) {
    Tuple2<Expression<Ref>, Type<Ref>> self = that.self.acceptVisitor(this);

    Optional<Class<Nameable>> definingClass = isTypeWithMembers(self.v2);

    if (!definingClass.isPresent()) {
      // TODO - Names
      throw new SemanticError(that.range(), "only classes have fields");
    }

    Optional<Field<Nameable>> fieldOpt =
        definingClass
            .get()
            .fields
            .stream()
            .filter(f -> f.name().equals(that.field.name()))
            .findFirst();

    if (!fieldOpt.isPresent()) {
      // TODO - Names
      throw new SemanticError(
          that.range(),
          "Class " + definingClass.get().name() + " has no field " + that.field.name());
    }

    Field<Nameable> field = fieldOpt.get();
    field.definingClass = self.v2;
    Type<Ref> returnType = field.type.acceptVisitor(this);
    return tuple(new Expression.FieldAccess<>(self.v1, new Ref(field), that.range), returnType);
  }

  @Override
  public Tuple2<Expression<Ref>, Type<Ref>> visitArrayAccess(
      Expression.ArrayAccess<? extends Nameable> that) {
    Tuple2<Expression<Ref>, Type<Ref>> arr = that.array.acceptVisitor(this);
    Tuple2<Expression<Ref>, Type<Ref>> idx = that.index.acceptVisitor(this);

    checkType(Type.INT, idx.v2);
    checkElementTypeIsNotVoid(arr.v2);
    checkIsArrayType(arr.v2);

    Type<Ref> returnType = new Type<>(arr.v2.typeRef, arr.v2.dimension - 1, arr.v2.range());
    return tuple(new Expression.ArrayAccess<>(arr.v1, idx.v1, that.range()), returnType);
  }

  @Override
  public Tuple2<Expression<Ref>, Type<Ref>> visitNewObject(NewObject<? extends Nameable> that) {
    Optional<Definition> optDef = types.lookup(that.type.name());
    if (!optDef.isPresent()) {
      throw new SemanticError(that.range(), "Type is not present");
    }
    // This actually should never happen to begin with..
    // The parser will not produce such a type.
    if (!(optDef.get() instanceof Class)) {
      throw new SemanticError(that.range(), "Only reference types can be allocated with new.");
    }

    Ref ref = new Ref(optDef.get());
    Type<Ref> returnType = new Type<>(ref, 0, optDef.get().range());
    return tuple(new NewObject<>(new Ref(optDef.get()), that.range()), returnType);
  }

  @Override
  public Tuple2<Expression<Ref>, Type<Ref>> visitNewArray(NewArray<? extends Nameable> that) {
    Type<Ref> type = that.type.acceptVisitor(this);
    Tuple2<Expression<Ref>, Type<Ref>> size = that.size.acceptVisitor(this);

    checkType(Type.INT, size.v2);

    // TODO - discuss: The +1 will probably introduce a bug, but I find it much more intuitive to increment dimension
    // here than in the parser, e.g. NewArray.type should denote the type of the elements of the new array
    Type<Ref> returnType = new Type<>(type.typeRef, type.dimension + 1, type.range());
    return tuple(new NewArray<>(type, size.v1, that.range()), returnType);
  }

  @Override
  public Tuple2<Expression<Ref>, Type<Ref>> visitVariable(
      Expression.Variable<? extends Nameable> that) {
    Optional<Definition> varOpt = locals.lookup(that.var.name());
    if (varOpt.isPresent()) {
      // is it a local var decl or a parameter?
      if (varOpt.get() instanceof BlockStatement.Variable) {
        Statement.Variable<Ref> decl = (Statement.Variable<Ref>) varOpt.get();
        return tuple(new Expression.Variable<>(new Ref(decl), that.range), decl.type);
      } else if (varOpt.get() instanceof Parameter) {
        Parameter<Ref> p = (Parameter<Ref>) varOpt.get();
        // Because of a hack where we represent main's parameter with type void, we have to check
        // if the expression is a variable of type void.
        checkElementTypeIsNotVoid(p.type);
        return tuple(new Expression.Variable<>(new Ref(p), that.range), p.type);
      } else {
        // We must have put something else into locals, this mustn't happen.
        assert false;
      }
    }

    // So it wasn't a local var... Maybe it was a field of the enclosing class
    Optional<Field<? extends Nameable>> fieldOpt = fields.lookup(that.var.name());

    if (fieldOpt.isPresent()) {
      // Analyze as if there was a preceding 'this.' in front of the variable
      // The field is there, so we can let errors pass through without causing confusion
      return new Expression.FieldAccess<>(THIS_EXPR, that.var, that.range).acceptVisitor(this);
    }

    throw new SemanticError(
        that.range(),
        "Variable '" + that.var.name() + "' used at " + that.range() + " was not in scope.");
  }

  @Override
  public Tuple2<Expression<Ref>, Type<Ref>> visitBooleanLiteral(
      BooleanLiteral<? extends Nameable> that) {
    // type parameter is not used by BooleanLiteral, cast is safe
    return tuple((BooleanLiteral<Ref>) that, Type.BOOLEAN);
  }

  @Override
  public Tuple2<Expression<Ref>, Type<Ref>> visitIntegerLiteral(
      IntegerLiteral<? extends Nameable> that) {

    if (Ints.tryParse(that.literal, 10) == null) {
      // insert range
      throw new SemanticError(
          that.range(), "The literal '" + that.literal + "' is not a valid 32-bit number");
    }
    // type parameter is not used by IntegerLiteral, cast is safe
    return tuple((IntegerLiteral<Ref>) that, Type.INT);
  }

  @Override
  public Tuple2<Expression<Ref>, Type<Ref>> visitReferenceTypeLiteral(
      Expression.ReferenceTypeLiteral<? extends Nameable> that) {
    if (that.name().equals("null")) {
      return tuple((Expression.ReferenceTypeLiteral<Ref>) that, Type.ANY);
    }
    assert that.name().equals("this");

    if (currentMethod.isStatic) {
      throw new SemanticError(
          that.range(), "Can't access 'this' in a static method at " + that.range());
    }

    return tuple((Expression.ReferenceTypeLiteral<Ref>) that, currentClass);
  }
}
