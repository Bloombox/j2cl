/*
 * Copyright 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.ast.visitors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.MoreCollectors;
import com.google.common.collect.Multimap;
import com.google.j2cl.ast.AbstractVisitor;
import com.google.j2cl.ast.ArrayTypeDescriptor;
import com.google.j2cl.ast.AstUtils;
import com.google.j2cl.ast.BinaryExpression;
import com.google.j2cl.ast.BinaryOperator;
import com.google.j2cl.ast.CastExpression;
import com.google.j2cl.ast.CompilationUnit;
import com.google.j2cl.ast.DeclaredTypeDescriptor;
import com.google.j2cl.ast.Expression;
import com.google.j2cl.ast.ExpressionStatement;
import com.google.j2cl.ast.Field;
import com.google.j2cl.ast.FieldAccess;
import com.google.j2cl.ast.FieldDescriptor;
import com.google.j2cl.ast.FunctionExpression;
import com.google.j2cl.ast.HasJsNameInfo;
import com.google.j2cl.ast.HasReadableDescription;
import com.google.j2cl.ast.HasSourcePosition;
import com.google.j2cl.ast.InstanceOfExpression;
import com.google.j2cl.ast.IntersectionTypeDescriptor;
import com.google.j2cl.ast.JsEnumInfo;
import com.google.j2cl.ast.JsMemberType;
import com.google.j2cl.ast.JsUtils;
import com.google.j2cl.ast.Member;
import com.google.j2cl.ast.MemberDescriptor;
import com.google.j2cl.ast.Method;
import com.google.j2cl.ast.MethodCall;
import com.google.j2cl.ast.MethodDescriptor;
import com.google.j2cl.ast.MethodDescriptor.ParameterDescriptor;
import com.google.j2cl.ast.MethodLike;
import com.google.j2cl.ast.NewArray;
import com.google.j2cl.ast.NewInstance;
import com.google.j2cl.ast.Statement;
import com.google.j2cl.ast.StringLiteral;
import com.google.j2cl.ast.SuperReference;
import com.google.j2cl.ast.ThisReference;
import com.google.j2cl.ast.Type;
import com.google.j2cl.ast.TypeDeclaration;
import com.google.j2cl.ast.TypeDescriptor;
import com.google.j2cl.ast.TypeDescriptors;
import com.google.j2cl.ast.TypeLiteral;
import com.google.j2cl.ast.Variable;
import com.google.j2cl.ast.VariableReference;
import com.google.j2cl.ast.visitors.ConversionContextVisitor.ContextRewriter;
import com.google.j2cl.common.Problems;
import com.google.j2cl.common.SourcePosition;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Checks and throws errors for invalid JsInterop constructs. */
public class JsInteropRestrictionsChecker {

  public static void check(List<CompilationUnit> compilationUnits, Problems problems) {
    new JsInteropRestrictionsChecker(problems).checkCompilationUnits(compilationUnits);
  }

  private final Problems problems;
  private boolean wasUnusableByJsWarningReported = false;

  private JsInteropRestrictionsChecker(Problems problems) {
    this.problems = problems;
  }

  private void checkCompilationUnits(List<CompilationUnit> compilationUnits) {
    for (CompilationUnit compilationUnit : compilationUnits) {
      checkCompilationUnit(compilationUnit);
    }
    if (wasUnusableByJsWarningReported) {
      problems.info(
          "Suppress \"[unusable-by-js]\" warnings by adding a "
              + "`@SuppressWarnings(\"unusable-by-js\")` annotation to the corresponding member.");
    }
  }

  private void checkCompilationUnit(CompilationUnit compilationUnit) {
    for (Type type : compilationUnit.getTypes()) {
      checkType(type);
    }
  }

  private void checkType(Type type) {
    TypeDeclaration typeDeclaration = type.getDeclaration();

    if (typeDeclaration.isJsType()) {
      if (!checkJsType(type)) {
        return;
      }
    }

    if (typeDeclaration.isJsEnum()) {
      checkJsEnum(type);
    }

    if (typeDeclaration.isJsEnum() || typeDeclaration.isJsType()) {
      checkQualifiedJsName(type);
    }

    if (typeDeclaration.isJsFunctionInterface()) {
      checkJsFunction(type);
    } else if (typeDeclaration.isJsFunctionImplementation()) {
      checkJsFunctionImplementation(type);
    } else {
      checkJsFunctionSubtype(type);
      if (checkJsConstructors(type)) {
        checkJsConstructorSubtype(type);
      }
    }

    checkTypeVariables(type);
    checkSuperTypes(type);

    Multimap<String, MemberDescriptor> instanceJsMembersByName =
        collectInstanceNames(type.getTypeDescriptor());
    Multimap<String, MemberDescriptor> staticJsMembersByName =
        collectStaticNames(type.getTypeDescriptor());
    for (Member member : type.getMembers()) {
      checkMember(member, instanceJsMembersByName, staticJsMembersByName);
    }
    checkTypeReferences(type);
    checkJsEnumUsages(type);
    checkJsFunctionLambdas(type);
    checkSystemProperties(type);
  }

  private void checkSystemProperties(Type type) {
    type.accept(
        new AbstractVisitor() {
          @Override
          public void exitMethodCall(MethodCall methodCall) {
            MethodDescriptor target = methodCall.getTarget();
            List<Expression> args = methodCall.getArguments();
            if (target
                    .getEnclosingTypeDescriptor()
                    .getQualifiedBinaryName()
                    .equals("java.lang.System")
                && target.getName().equals("getProperty")
                && !(args.get(0) instanceof StringLiteral)) {
              problems.error(
                  methodCall.getSourcePosition(),
                  "Method '%s' can only take a string literal as its first parameter",
                  target.getReadableDescription());
            }
          }
        });
  }

  private void checkJsFunctionLambdas(Type type) {
    type.accept(
        new AbstractVisitor() {
          @Override
          public void exitFunctionExpression(FunctionExpression functionExpression) {
            if (!functionExpression.getTypeDescriptor().isIntersection()) {
              return;
            }
            IntersectionTypeDescriptor intersectionTypeDescriptor =
                (IntersectionTypeDescriptor) functionExpression.getTypeDescriptor();
            if (intersectionTypeDescriptor.getIntersectionTypeDescriptors().stream()
                .anyMatch(TypeDescriptor::isJsFunctionInterface)) {
              problems.error(
                  functionExpression.getSourcePosition(),
                  "JsFunction lambda can only implement the JsFunction interface.");
            }
          }
        });
  }

  private void checkTypeVariables(Type type) {
    if (type.getDeclaration().getTypeParameterDescriptors().stream()
        .map(TypeDescriptor::toRawTypeDescriptor)
        .anyMatch(AstUtils::isNonNativeJsEnum)) {
      problems.error(
          type.getSourcePosition(),
          "Type '%s' cannot define a type variable with a JsEnum as a bound.",
          type.getReadableDescription());
    }
  }

  private void checkSuperTypes(Type type) {
    if (hasNonNativeJsEnumTypeArgument(type.getSuperTypeDescriptor())) {
      problems.error(
          type.getSourcePosition(),
          "Type '%s' cannot subclass a class parameterized by JsEnum. (b/118304241)",
          type.getReadableDescription());
    }

    if (type.getSuperInterfaceTypeDescriptors().stream()
        .anyMatch(JsInteropRestrictionsChecker::hasNonNativeJsEnumTypeArgument)) {
      problems.error(
          type.getSourcePosition(),
          "Type '%s' cannot implement an interface parameterized by JsEnum. (b/118304241)",
          type.getReadableDescription());
    }
  }

  private static boolean hasNonNativeJsEnumTypeArgument(DeclaredTypeDescriptor typeDescriptor) {
    return typeDescriptor != null
        && typeDescriptor.getTypeArgumentDescriptors().stream()
            .map(TypeDescriptor::toRawTypeDescriptor)
            .anyMatch(AstUtils::isNonNativeJsEnum);
  }

  /**
   * Checks that the JsEnum complies with all restrictions.
   *
   * <p>There are three flavors of JsEnum:
   *
   * <ul>
   *   <li>(1) JsEnum that does not customize the value type.
   *   <li>(2) JsEnum that customizes the value type.
   *   <li>(3) native JsEnum.
   * </ul>
   *
   * <pre>
   *       | has value field | has constructor | implements Comparable | can call ordinal() |
   *  (1)  |                 |                 |          x            |        x           |
   *  (2)  |       x         |        x        |                       |                    |
   *  (3)  |       x         |                 |                       |                    |
   *
   * </pre>
   */
  private void checkJsEnum(Type type) {
    JsEnumInfo jsEnumInfo = type.getTypeDescriptor().getJsEnumInfo();

    if (!type.isEnum()) {
      problems.error(
          type.getSourcePosition(),
          "JsEnum '%s' has to be an enum type.",
          type.getReadableDescription());
      return;
    }

    if (type.getDeclaration().isJsType()) {
      problems.error(
          type.getSourcePosition(),
          "'%s' cannot be both a JsEnum and a JsType at the same time.",
          type.getReadableDescription());
    }

    Field valueField = getJsEnumValueField(type);
    if (valueField == null && jsEnumInfo.hasCustomValue()) {
      problems.error(
          type.getSourcePosition(),
          "Custom-valued JsEnum '%s' does not have a field named 'value'.",
          type.getReadableDescription());
    } else if (valueField != null && !jsEnumInfo.hasCustomValue()) {
      problems.error(
          type.getSourcePosition(),
          "Non-custom-valued JsEnum '%s' cannot have a field named 'value'.",
          type.getReadableDescription());
    }

    if (type.getConstructors().isEmpty() && requiresConstructor(type.getDeclaration())) {
      problems.error(
          type.getSourcePosition(),
          "Custom-valued JsEnum '%s' should have a constructor.",
          type.getReadableDescription());
    }

    if (!type.getSuperInterfaceTypeDescriptors().isEmpty()) {
      problems.error(
          type.getSourcePosition(),
          "JsEnum '%s' cannot implement any interface.",
          type.getReadableDescription());
    }

    for (Member member : type.getMembers()) {
      if (member.getDescriptor().isJsOverlay()) {
        // JsOverlays are checked independently.
        continue;
      }
      checkMemberOfJsEnum(type, member);
    }
  }

  private static Field getJsEnumValueField(Type type) {
    checkState(type.getDeclaration().isJsEnum());
    return type.getFields().stream()
        .filter(member -> AstUtils.isJsEnumCustomValueField(member.getDescriptor()))
        .findFirst()
        .orElse(null);
  }

  private boolean requiresConstructor(TypeDeclaration typeDeclaration) {
    return typeDeclaration.getJsEnumInfo().hasCustomValue() && !typeDeclaration.isNative();
  }

  private void checkMemberOfJsEnum(Type type, Member member) {
    if (member.isEnumField()) {
      checkJsEnumConstant(type, (Field) member);
    } else if (AstUtils.isJsEnumCustomValueField(member.getDescriptor())) {
      checkJsEnumValueField((Field) member);
    } else if (member.isField() && !member.isStatic()) {
      problems.error(
          member.getSourcePosition(),
          "JsEnum '%s' cannot have instance field '%s'.",
          type.getReadableDescription(),
          member.getReadableDescription());
    } else if (member.isInitializerBlock() && !member.isStatic()) {
      problems.error(
          member.getSourcePosition(),
          "JsEnum '%s' cannot have an instance initializer.",
          type.getReadableDescription());
    } else {
      if (member.isConstructor()) {
        checkJsEnumConstructor(
            (Method) member, AstUtils.getJsEnumValueFieldType(type.getDeclaration()));
      } else if (type.isNative()) {
        checkMustBeJsOverlay(member, "Native JsEnum");
      }
      checkImplementableStatically(member, "JsEnum");
    }
  }

  private static boolean canDefineValueField(TypeDescriptor typeDescriptor) {
    return typeDescriptor.getJsEnumInfo().hasCustomValue();
  }

  private void checkJsEnumConstant(Type type, Field field) {
    if (!field.getInitializer().getTypeDescriptor().isSameBaseType(type.getTypeDescriptor())) {
      problems.error(
          field.getSourcePosition(),
          "JsEnum constant '%s' cannot have a class body.",
          field.getReadableDescription());
      return;
    }

    if (!canDefineValueField(type.getTypeDescriptor())) {
      // Non-custom-valued enum is already invalid if it has a value field,
      // further constant related checking is unnecessary.
      return;
    }

    Expression enumFieldValue = getEnumConstantValue(field);
    if (enumFieldValue == null || enumFieldValue.isCompileTimeConstant()) {
      return;
    }
    problems.error(
        field.getSourcePosition(),
        "Custom-valued JsEnum constant '%s' cannot have a non-literal value.",
        field.getReadableDescription());
  }

  private Expression getEnumConstantValue(Field field) {
    NewInstance initializer = (NewInstance) field.getInitializer();
    List<Expression> arguments = initializer.getArguments();
    if (arguments.size() != 1) {
      // Not a valid initialization. The code will be rejected.
      return null;
    }
    return arguments.get(0);
  }

  private void checkJsEnumValueField(Field field) {
    FieldDescriptor fieldDescriptor = field.getDescriptor();

    if (!canDefineValueField(fieldDescriptor.getEnclosingTypeDescriptor())) {
      // Non-custom-valued enum is already invalid if it has a value field,
      // further value related checking is unnecessary.
      return;
    }

    TypeDescriptor valueTypeDescriptor = fieldDescriptor.getTypeDescriptor();
    String messagePrefix =
        String.format("Custom-valued JsEnum value field '%s'", field.getReadableDescription());

    if (fieldDescriptor.isStatic()
        || fieldDescriptor.isJsOverlay()
        || fieldDescriptor.isJsMember()) {
      problems.error(
          field.getSourcePosition(),
          messagePrefix + " cannot be static nor JsOverlay nor JsMethod nor JsProperty.");
    }

    if (!checkJsEnumCustomValueType(valueTypeDescriptor)) {
      problems.error(
          field.getSourcePosition(),
          messagePrefix + " cannot have the type '%s'.",
          valueTypeDescriptor.getReadableDescription());
    }

    if (field.getInitializer() != null) {
      problems.error(field.getSourcePosition(), messagePrefix + " cannot have initializer.");
    }
  }

  private static boolean checkJsEnumCustomValueType(TypeDescriptor valueTypeDescriptor) {
    return (valueTypeDescriptor.isPrimitive()
            && !TypeDescriptors.isPrimitiveLong(valueTypeDescriptor))
        || TypeDescriptors.isJavaLangString(valueTypeDescriptor);
  }

  private void checkJsEnumConstructor(Method constructor, TypeDescriptor customValueType) {
    TypeDeclaration enclosingTypeDeclaration =
        constructor.getDescriptor().getEnclosingTypeDescriptor().getTypeDeclaration();
    if (!requiresConstructor(enclosingTypeDeclaration)) {
      problems.error(
          constructor.getSourcePosition(),
          getJsEnumTypeText(enclosingTypeDeclaration) + " '%s' cannot have constructor '%s'.",
          enclosingTypeDeclaration.getReadableDescription(),
          constructor.getReadableDescription());
      return;
    }

    if (checkCustomValuedJsEnumConstructor(constructor, customValueType)) {
      return;
    }
    problems.error(
        constructor.getSourcePosition(),
        "Custom-valued JsEnum constructor '%s' should have one parameter of the value type and its "
            + "body should only be the assignment to the value field.",
        constructor.getReadableDescription());
  }

  /**
   * Custom valued JsEnums must have exactly one constructor of the following form:
   *
   * <pre>{@code
   * JsEnumType(CustomValueType parameter) {
   *   this.value = parameter;
   * }
   * }</pre>
   */
  private static boolean checkCustomValuedJsEnumConstructor(
      Method constructor, TypeDescriptor customValueType) {
    MethodDescriptor constructorDescriptor = constructor.getDescriptor();
    // Check that the parameter to the constructor is consistent with the custom value type.
    if (constructorDescriptor.getParameterDescriptors().size() != 1
        || !constructorDescriptor
            .getParameterDescriptors()
            .get(0)
            .getTypeDescriptor()
            .isSameBaseType(customValueType)) {
      // Method declaration is invalid.
      return false;
    }

    // Skip the super() call if present to get the only expected statement in the custom valued
    // JsEnum.
    int statementIndex = AstUtils.hasSuperCall(constructor) ? 1 : 0;

    // Verify that the body only contains the assignment to the custom value field.
    return constructor.getBody().getStatements().size() == statementIndex + 1
        && checkJsEnumConstructorStatement(
            constructorDescriptor.getEnclosingTypeDescriptor(),
            constructor.getBody().getStatements().get(statementIndex),
            constructor.getParameters().get(0));
  }

  /**
   * Checks that the only statement in a custom valued JsEnum is of the form:
   *
   * <pre>{@code
   * this.value = parameter;
   * }</pre>
   */
  private static boolean checkJsEnumConstructorStatement(
      DeclaredTypeDescriptor typeDescriptor, Statement statement, Variable valueParameter) {
    if (!(statement instanceof ExpressionStatement)) {
      return false;
    }
    Expression expression = ((ExpressionStatement) statement).getExpression();
    if (!(expression instanceof BinaryExpression)) {
      return false;
    }

    BinaryExpression binaryExpression = (BinaryExpression) expression;
    if (binaryExpression.getOperator() != BinaryOperator.ASSIGN
        || !(binaryExpression.getRightOperand() instanceof VariableReference)
        || !(binaryExpression.getLeftOperand() instanceof FieldAccess)) {
      return false;
    }
    FieldAccess lhs = (FieldAccess) binaryExpression.getLeftOperand();
    Variable variable = ((VariableReference) binaryExpression.getRightOperand()).getTarget();

    return (lhs.getQualifier() == null || lhs.getQualifier() instanceof ThisReference)
        && lhs.getTarget().isMemberOf(typeDescriptor)
        && AstUtils.isJsEnumCustomValueField(lhs.getTarget())
        && variable == valueParameter;
  }

  private void checkJsEnumUsages(Type type) {
    checkJsEnumMethodCalls(type);
    checkJsEnumAssignments(type);
    checkJsEnumArrays(type);
    checkJsEnumValueFieldAssignment(type);
  }

  private void checkJsEnumMethodCalls(Type type) {
    type.accept(
        new AbstractVisitor() {
          @Override
          public void exitMethodCall(MethodCall methodCall) {
            MethodDescriptor target = methodCall.getTarget();

            TypeDescriptor qualifierTypeDescriptor =
                target.isStatic()
                    ? target.getEnclosingTypeDescriptor()
                    : methodCall.getQualifier().getTypeDescriptor();
            if (!qualifierTypeDescriptor.isJsEnum()) {
              // If the actual target of the method is not a JsEnum, nothing to check.
              return;
            }

            if (target.getEnclosingTypeDescriptor().isJsEnum() && !target.isEnumSyntheticMethod()) {
              // Methods declared by the user in JsEnum are callable.
              return;
            }

            if (target.isOrOverridesJavaLangObjectMethod()) {
              return;
            }

            String messagePrefix = "JsEnum";

            String targetMethodSignature = target.getDeclarationDescriptor().getMethodSignature();
            if (targetMethodSignature.equals("compareTo(java.lang.Enum)")) {
              if (qualifierTypeDescriptor.getJsEnumInfo().supportsComparable()) {
                return;
              }
              // Customize the message to give a better idea why compareTo() is forbidden.
              messagePrefix = getJsEnumTypeText(qualifierTypeDescriptor);
            }
            if (targetMethodSignature.equals("ordinal()")) {
              if (qualifierTypeDescriptor.getJsEnumInfo().supportsOrdinal()) {
                return;
              }
              // Customize the message to give a better idea why ordinal() is forbidden.
              messagePrefix = getJsEnumTypeText(qualifierTypeDescriptor);
            }

            String bugMessage = "";
            if (targetMethodSignature.equals("values()")) {
              bugMessage = " (b/118228329)";
            }

            problems.error(
                methodCall.getSourcePosition(),
                messagePrefix + " '%s' does not support '%s'." + bugMessage,
                qualifierTypeDescriptor.getReadableDescription(),
                target.getReadableDescription());
          }
        });
  }

  private String getJsEnumTypeText(TypeDeclaration typeDeclaration) {
    return getJsEnumTypeText(typeDeclaration.toUnparameterizedTypeDescriptor());
  }

  private String getJsEnumTypeText(TypeDescriptor typeDescriptor) {
    checkArgument(typeDescriptor.isJsEnum());
    if (typeDescriptor.isNative()) {
      return "Native JsEnum";
    }

    if (typeDescriptor.getJsEnumInfo().hasCustomValue()) {
      return "Custom-valued JsEnum";
    }

    return "Non-custom-valued JsEnum";
  }

  private void checkJsEnumAssignments(Type type) {
    type.getMembers().forEach(this::checkJsEnumAssignments);
  }

  private void checkJsEnumAssignments(Member member) {
    member.accept(
        new ConversionContextVisitor(
            new ContextRewriter() {
              @Override
              public Expression rewriteTypeConversionContext(
                  TypeDescriptor inferredTypeDescriptor,
                  TypeDescriptor declaredTypeDescriptor,
                  Expression expression) {
                // Handle here all the scenarios related to the JsEnum being used as an enum, e.g.
                // assignment, parameter passing, etc.
                checkJsEnumAssignment(inferredTypeDescriptor, expression);
                return expression;
              }

              @Override
              public Expression rewriteMemberQualifierContext(
                  TypeDescriptor inferredTypeDescriptor,
                  TypeDescriptor declaredTypeDescriptor,
                  Expression qualifierExpression) {
                // Skip checking here explicitly, members are checked explicitly elsewhere.
                return qualifierExpression;
              }

              private void checkJsEnumAssignment(
                  TypeDescriptor toTypeDescriptor, Expression expression) {
                TypeDescriptor expressionTypeDescriptor = expression.getTypeDescriptor();
                if (!expressionTypeDescriptor.isJsEnum() || toTypeDescriptor.isJsEnum()) {
                  return;
                }

                TypeDescriptor targetRawTypeDescriptor = toTypeDescriptor.toRawTypeDescriptor();
                if (TypeDescriptors.isJavaLangObject(targetRawTypeDescriptor)) {
                  return;
                }

                if (TypeDescriptors.isJavaIoSerializable(targetRawTypeDescriptor)) {
                  return;
                }

                String messagePrefix = "JsEnum";
                if (TypeDescriptors.isJavaLangComparable(targetRawTypeDescriptor)) {
                  if (expressionTypeDescriptor.getJsEnumInfo().supportsComparable()) {
                    return;
                  }
                  messagePrefix = getJsEnumTypeText(expressionTypeDescriptor);
                }

                // TODO(b/65465035): When source position is tracked at the expression level,
                // the error reporting here should include source position.
                problems.error(
                    member.getSourcePosition(),
                    messagePrefix + " '%s' cannot be assigned to '%s'.",
                    expressionTypeDescriptor.getReadableDescription(),
                    toTypeDescriptor.getReadableDescription());
              }
            }));
  }

  private void checkJsEnumArrays(Type type) {
    type.accept(
        new AbstractVisitor() {
          @Override
          public void exitVariable(Variable variable) {
            if (variable.isParameter()) {
              // Parameters are checked at the declaration site to give a better error message.
              return;
            }
            TypeDescriptor variableTypeDescriptor = variable.getTypeDescriptor();
            String messagePrefix = String.format("Variable '%s'", variable.getName());
            errorIfNonNativeJsEnumArray(
                variableTypeDescriptor,
                variable.getSourcePosition().orElse(getCurrentMember().getSourcePosition()),
                messagePrefix);
          }

          @Override
          public void exitMethod(Method method) {
            checkParametersAndReturnType(method);
          }

          @Override
          public void exitFunctionExpression(FunctionExpression functionExpression) {
            checkParametersAndReturnType(functionExpression);
          }

          @Override
          public void exitField(Field field) {
            FieldDescriptor fieldDescriptor = field.getDescriptor();
            TypeDescriptor fieldTypeDescriptor = fieldDescriptor.getTypeDescriptor();
            String messagePrefix = String.format("Field '%s'", field.getReadableDescription());
            errorIfNonNativeJsEnumArray(
                fieldTypeDescriptor, field.getSourcePosition(), messagePrefix);
          }

          @Override
          public void exitFieldAccess(FieldAccess fieldAccess) {
            TypeDescriptor inferredTypeDescriptor = fieldAccess.getTypeDescriptor();
            TypeDescriptor declaredTypeDescriptor =
                fieldAccess.getTarget().getDeclarationDescriptor().getTypeDescriptor();
            if (inferredTypeDescriptor.equals(declaredTypeDescriptor)) {
              // No inference, the error will be given at declaration if needed.
              return;
            }
            String messagePrefix =
                String.format(
                    "Reference to field '%s'", fieldAccess.getTarget().getReadableDescription());
            errorIfNonNativeJsEnumArray(
                inferredTypeDescriptor, getCurrentMember().getSourcePosition(), messagePrefix);
          }

          @Override
          public void exitMethodCall(MethodCall methodCall) {
            TypeDescriptor inferredTypeDescriptor =
                methodCall.getTarget().getReturnTypeDescriptor();
            TypeDescriptor declaredTypeDescriptor =
                methodCall.getTarget().getDeclarationDescriptor().getReturnTypeDescriptor();
            if (inferredTypeDescriptor.equals(declaredTypeDescriptor)) {
              // No inference, the error will be given at declaration if needed.
              return;
            }
            String messagePrefix =
                String.format(
                    "Returned type in call to method '%s'",
                    methodCall.getTarget().getReadableDescription());
            errorIfNonNativeJsEnumArray(
                inferredTypeDescriptor, getCurrentMember().getSourcePosition(), messagePrefix);
          }

          @Override
          public void exitNewArray(NewArray newArray) {
            ArrayTypeDescriptor newArrayTypeDescriptor = newArray.getTypeDescriptor();
            // TODO(b/65465035): Emit the expression source position when it is tracked, and avoid
            // toString() in an AST nodes.
            String messagePrefix = String.format("Array creation '%s'", newArray);
            errorIfNonNativeJsEnumArray(
                newArrayTypeDescriptor, getCurrentMember().getSourcePosition(), messagePrefix);
          }
        });
  }

  private void checkParametersAndReturnType(MethodLike methodLike) {
    for (Variable parameter : methodLike.getParameters()) {
      TypeDescriptor parameterTypeDescriptor = parameter.getTypeDescriptor();
      String messagePrefix =
          String.format(
              "Parameter '%s' in '%s'", parameter.getName(), methodLike.getReadableDescription());
      errorIfNonNativeJsEnumArray(
          parameterTypeDescriptor,
          parameter.getSourcePosition().orElse(methodLike.getSourcePosition()),
          messagePrefix);
    }

    if (methodLike.getDescriptor() == null) {
      // TODO(b/115566064): Emit the correct error for lambdas that are not JsFunctions and
      // return JsEnum arrays.
      return;
    }
    TypeDescriptor returnTypeDescriptor = methodLike.getDescriptor().getReturnTypeDescriptor();
    String messagePrefix =
        String.format("Return type of '%s'", methodLike.getReadableDescription());
    errorIfNonNativeJsEnumArray(
        returnTypeDescriptor, methodLike.getSourcePosition(), messagePrefix);
  }

  private void checkJsEnumValueFieldAssignment(Type type) {
    type.accept(
        new AbstractVisitor() {
          @Override
          public void exitBinaryExpression(BinaryExpression binaryExpression) {
            if (!binaryExpression.getOperator().isAssignmentOperator()) {
              return;
            }
            Expression lhs = binaryExpression.getLeftOperand();
            if (!(lhs instanceof FieldAccess)) {
              return;
            }

            FieldAccess fieldAccess = (FieldAccess) lhs;
            FieldDescriptor fieldDescriptor = fieldAccess.getTarget();
            if (!AstUtils.isJsEnumCustomValueField(fieldDescriptor)) {
              return;
            }

            if (getCurrentMember().isConstructor()
                && getCurrentMember().getDescriptor().getEnclosingTypeDescriptor().isJsEnum()) {
              // JsEnum constructors have more stringent checks elsewhere.
              return;
            }
            problems.error(
                fieldAccess.getSourcePosition().orElse(type.getSourcePosition()),
                "Custom-valued JsEnum value field '%s' cannot be assigned.",
                fieldDescriptor.getReadableDescription());
          }
        });
  }

  /** Checks that type references in casts, instanceof and type literals are valid. */
  private void checkTypeReferences(Type type) {
    type.accept(
        new AbstractVisitor() {
          @Override
          public void exitInstanceOfExpression(InstanceOfExpression instanceOfExpression) {
            TypeDescriptor testTypeDescriptor = instanceOfExpression.getTestTypeDescriptor();
            if (testTypeDescriptor.isNative() && testTypeDescriptor.isInterface()) {
              problems.error(
                  instanceOfExpression.getSourcePosition(),
                  "Cannot do instanceof against native JsType interface '%s'.",
                  testTypeDescriptor.getReadableDescription());
            } else if (testTypeDescriptor.isJsFunctionImplementation()) {
              problems.error(
                  instanceOfExpression.getSourcePosition(),
                  "Cannot do instanceof against JsFunction implementation '%s'.",
                  testTypeDescriptor.getReadableDescription());
            } else if (testTypeDescriptor.isJsEnum() && testTypeDescriptor.isNative()) {
              problems.error(
                  instanceOfExpression.getSourcePosition(),
                  "Cannot do instanceof against native JsEnum '%s'.",
                  testTypeDescriptor.getReadableDescription());
            } else if (hasNonNativeJsEnumArray(testTypeDescriptor)) {
              problems.error(
                  instanceOfExpression.getSourcePosition(),
                  "Cannot do instanceof against JsEnum array '%s'. (b/118299062)",
                  testTypeDescriptor.getReadableDescription());
            }
          }

          @Override
          public void exitCastExpression(CastExpression castExpression) {
            TypeDescriptor castTypeDescriptor = castExpression.getCastTypeDescriptor();
            if (hasNonNativeJsEnumArray(castTypeDescriptor)) {
              // TODO(b/65465035): Emit the expression source position when it is tracked.
              problems.error(
                  getCurrentMember().getSourcePosition(),
                  "Cannot cast to JsEnum array '%s'. (b/118299062)",
                  castTypeDescriptor.getReadableDescription());
            }
          }

          @Override
          public void exitTypeLiteral(TypeLiteral typeLiteral) {
            TypeDescriptor literalTypeDescriptor = typeLiteral.getReferencedTypeDescriptor();
            if (literalTypeDescriptor.isJsEnum() && literalTypeDescriptor.isNative()) {
              problems.error(
                  typeLiteral.getSourcePosition(),
                  "Cannot use native JsEnum literal '%s.class'.",
                  literalTypeDescriptor.getReadableDescription());
            }
          }
        });
  }

  private void errorIfNonNativeJsEnumArray(
      TypeDescriptor typeDescriptor, SourcePosition sourcePosition, String messagePrefix) {
    if (hasNonNativeJsEnumArray(typeDescriptor)) {
      problems.error(
          sourcePosition,
          messagePrefix + " cannot be of type '%s'. (b/118299062)",
          typeDescriptor.getReadableDescription());
    }
  }

  private static boolean hasNonNativeJsEnumArray(TypeDescriptor typeDescriptor) {
    if (AstUtils.isNonNativeJsEnumArray(typeDescriptor)) {
      return true;
    }
    if (typeDescriptor instanceof DeclaredTypeDescriptor) {
      DeclaredTypeDescriptor declaredTypeDescriptor = (DeclaredTypeDescriptor) typeDescriptor;
      return declaredTypeDescriptor.getTypeArgumentDescriptors().stream()
          .anyMatch(JsInteropRestrictionsChecker::hasNonNativeJsEnumArray);
    }
    return false;
  }

  private boolean checkJsType(Type type) {
    TypeDeclaration typeDeclaration = type.getDeclaration();
    if (typeDeclaration.isLocal()) {
      problems.error(
          type.getSourcePosition(),
          "Local class '%s' cannot be a JsType.",
          type.getDeclaration().getReadableDescription());
      return false;
    }

    if (typeDeclaration.isNative()) {
      if (!checkNativeJsType(type)) {
        return false;
      }
    }

    return true;
  }

  private void checkIllegalOverrides(Method method) {
    Optional<MethodDescriptor> jsOverlayOverride =
        method
            .getDescriptor()
            .getOverriddenMethodDescriptors()
            .stream()
            .filter(MethodDescriptor::isJsOverlay)
            .findFirst();

    if (jsOverlayOverride.isPresent()) {
      checkState(!jsOverlayOverride.get().isSynthetic());
      problems.error(
          method.getSourcePosition(),
          "Method '%s' cannot override a JsOverlay method '%s'.",
          method.getReadableDescription(),
          jsOverlayOverride.get().getReadableDescription());
    }

    if (AstUtils.isNonNativeJsEnum(method.getDescriptor().getReturnTypeDescriptor())) {
      Optional<MethodDescriptor> nonJsEnumReturnOverride =
          method.getDescriptor().getOverriddenMethodDescriptors().stream()
              .filter(m -> !m.getReturnTypeDescriptor().toRawTypeDescriptor().isJsEnum())
              .findFirst();

      if (nonJsEnumReturnOverride.isPresent()) {
        checkState(!nonJsEnumReturnOverride.get().isSynthetic());
        problems.error(
            method.getSourcePosition(),
            "Method '%s' returning JsEnum cannot override method '%s'. (b/118301700)",
            method.getReadableDescription(),
            nonJsEnumReturnOverride.get().getReadableDescription());
      }
    }
  }

  private void checkMember(
      Member member,
      Multimap<String, MemberDescriptor> instanceJsMembersByName,
      Multimap<String, MemberDescriptor> staticJsMembersByName) {
    MemberDescriptor memberDescriptor = member.getDescriptor();
    if ((!member.isMethod() && !member.isField()) || memberDescriptor.isSynthetic()) {
      return;
    }

    DeclaredTypeDescriptor enclosingTypeDescriptor = memberDescriptor.getEnclosingTypeDescriptor();
    if (enclosingTypeDescriptor.isNative() && enclosingTypeDescriptor.isJsType()) {
      checkMemberOfNativeJsType(member);
    }

    if (enclosingTypeDescriptor.extendsNativeClass()) {
      checkMemberOfSubclassOfNativeClass(member);
    }

    if (memberDescriptor.isJsOverlay()) {
      checkJsOverlay(member);
    }

    if (member.isMethod()) {
      Method method = (Method) member;
      checkIllegalOverrides(method);
      checkMethodParameters(method);

      if (memberDescriptor.isNative()) {
        checkNativeMethod(method);
      }
      if (memberDescriptor.isJsAsync()) {
        checkJsAsyncMethod(method);
      }
      if (!checkJsPropertyAccessor(method)) {
        return;
      }
    }

    if (memberDescriptor.canBeReferencedExternally()) {
      checkUnusableByJs(member);
    }

    if (!checkQualifiedJsName(member)) {
      // Do not check for name collisions if the member has an invalid name.
      // This avoids reporting cascading error that are irrelevant.
      return;
    }

    if (isInstanceJsMember(memberDescriptor)) {
      checkNameCollisions(instanceJsMembersByName, member);
    }

    if (isStaticJsMember(memberDescriptor)) {
      checkNameCollisions(staticJsMembersByName, member);
    }
  }

  private void checkMemberOfSubclassOfNativeClass(Member member) {
    if (member.isStatic() || member.isConstructor() || member.getDescriptor().isJsOverlay()) {
      return;
    }

    member.accept(
        new AbstractVisitor() {
          @Override
          public void exitMethodCall(MethodCall methodCall) {
            if (!(methodCall.getQualifier() instanceof SuperReference)) {
              return;
            }
            MethodDescriptor target = methodCall.getTarget();
            if (target.isOrOverridesJavaLangObjectMethod()) {
              problems.error(
                  methodCall.getSourcePosition(),
                  "Cannot use 'super' to call '%s' from a subclass of a native class.",
                  target.getReadableDescription());
            }
          }
        });
  }

  private void checkNativeMethod(Method method) {
    MethodDescriptor methodDescriptor = method.getDescriptor();
    if (isUnusableByJsSuppressed(methodDescriptor)) {
      return;
    }

    if (!methodDescriptor.isJsMember()) {
      problems.warning(
          method.getSourcePosition(),
          "[unusable-by-js] Native '%s' is exposed to JavaScript without @JsMethod.",
          method.getReadableDescription());
    }
  }

  private void checkJsAsyncMethod(Method method) {
    TypeDescriptor returnType = method.getDescriptor().getReturnTypeDescriptor();
    if (returnType instanceof DeclaredTypeDescriptor) {
      DeclaredTypeDescriptor returnTypeDescriptor = (DeclaredTypeDescriptor) returnType;
      String qualifiedJsName = returnTypeDescriptor.getQualifiedJsName();
      if (qualifiedJsName.equals("IThenable") || qualifiedJsName.equals("Promise")) {
        return;
      }
    }
    problems.error(
        method.getSourcePosition(),
        "JsAsync method '%s' should return either 'IThenable' or 'Promise' but returns '%s'.",
        method.getReadableDescription(),
        returnType.getReadableDescription());
  }

  private void checkOverrideConsistency(Member member) {
    if (!member.isMethod() || !member.getDescriptor().isJsMember()) {
      return;
    }
    Method method = (Method) member;
    String jsName = method.getSimpleJsName();
    for (MethodDescriptor overriddenMethodDescriptor :
        method.getDescriptor().getOverriddenMethodDescriptors()) {
      if (!overriddenMethodDescriptor.isJsMember()) {
        continue;
      }

      String parentName = overriddenMethodDescriptor.getSimpleJsName();
      if (overriddenMethodDescriptor.isJsMethod() != method.getDescriptor().isJsMethod()) {
        // Overrides can not change JsMethod to JsProperty nor vice versa.
        problems.error(
            method.getSourcePosition(),
            "%s '%s' cannot override %s '%s'.",
            member.getDescriptor().isJsMethod() ? "JsMethod" : "JsProperty",
            member.getReadableDescription(),
            overriddenMethodDescriptor.isJsMethod() ? "JsMethod" : "JsProperty",
            overriddenMethodDescriptor.getReadableDescription(),
            parentName);
        break;
      }

      if (!parentName.equals(jsName)) {
        problems.error(
            method.getSourcePosition(),
            "'%s' cannot be assigned JavaScript name '%s' that is different from the"
                + " JavaScript name of a method it overrides ('%s' with JavaScript name '%s').",
            member.getReadableDescription(),
            jsName,
            overriddenMethodDescriptor.getReadableDescription(),
            parentName);
        break;
      }
    }
  }

  private void checkQualifiedJsName(Type type) {
    if (type.getDeclaration().isStarOrUnknown()) {
      if (!type.isNative() || !type.isInterface() || !JsUtils.isGlobal(type.getJsNamespace())) {
        problems.error(
            type.getSourcePosition(),
            "Only native interfaces in the global namespace can be named '%s'.",
            type.getSimpleJsName());
      }
      return;
    }

    checkJsName(type);
    checkJsNamespace(type);
  }

  private boolean checkQualifiedJsName(Member member) {
    if (member.isConstructor()) {
      // Constructors always inherit their name and namespace from the enclosing type.
      // The corresponding checks are done for the type separately.
      return true;
    }

    if (!checkJsName(member)) {
      return false;
    }

    if (member.getJsNamespace() == null) {
      return true;
    }

    if (member
        .getJsNamespace()
        .equals(member.getDescriptor().getEnclosingTypeDescriptor().getQualifiedJsName())) {
      // Namespace set by the enclosing type has already been checked.
      return true;
    }

    if (!member.isStatic()) {
      problems.error(
          member.getSourcePosition(),
          "Instance member '%s' cannot declare a namespace.",
          member.getReadableDescription());
      return false;
    }

    if (!member.isNative()) {
      problems.error(
          member.getSourcePosition(),
          "Non-native member '%s' cannot declare a namespace.",
          member.getReadableDescription());
      return false;
    }

    return checkJsNamespace(member);
  }

  private void checkJsOverlay(Member member) {
    if (member.getDescriptor().isSynthetic()) {
      return;
    }

    MemberDescriptor memberDescriptor = member.getDescriptor();
    String readableDescription = memberDescriptor.getReadableDescription();
    if (!memberDescriptor.getEnclosingTypeDescriptor().isNative()
        && !memberDescriptor.getEnclosingTypeDescriptor().isJsFunctionInterface()) {
      problems.error(
          member.getSourcePosition(),
          "JsOverlay '%s' can only be declared in a native type or @JsFunction interface.",
          readableDescription);
    }

    if (memberDescriptor.isJsMember()) {
      problems.error(
          member.getSourcePosition(),
          "JsOverlay method '%s' cannot be nor override a JsProperty or a JsMethod.",
          readableDescription);
      return;
    }
    if (member.isMethod()) {
      if (!memberDescriptor.getEnclosingTypeDescriptor().getTypeDeclaration().isFinal()
          && !memberDescriptor.isFinal()
          && !memberDescriptor.isStatic()
          && !memberDescriptor.getVisibility().isPrivate()
          && !memberDescriptor.isDefaultMethod()) {
        problems.error(
            member.getSourcePosition(),
            "JsOverlay method '%s' cannot be non-final.",
            readableDescription);
        return;
      }
    }

    if (member.isField() && !memberDescriptor.isStatic()) {
      problems.error(
          member.getSourcePosition(),
          "JsOverlay field '%s' can only be static.",
          readableDescription);
    }

    checkImplementableStatically(member, "JsOverlay");
  }

  private boolean checkNativeJsType(Type type) {
    TypeDeclaration typeDeclaration = type.getDeclaration();
    String readableDescription = typeDeclaration.getReadableDescription();

    if (type.isEnumOrSubclass()) {
      problems.error(
          type.getSourcePosition(),
          "Enum '%s' cannot be a native JsType. Use '@JsEnum(isNative = true)' instead.",
          readableDescription);
      return false;
    }
    if (typeDeclaration.isCapturingEnclosingInstance()) {
      problems.error(
          type.getSourcePosition(),
          "Non static inner class '%s' cannot be a native JsType.",
          readableDescription);
      return false;
    }

    TypeDescriptor superTypeDescriptor = type.getSuperTypeDescriptor();
    if (superTypeDescriptor != null
        && !TypeDescriptors.isJavaLangObject(superTypeDescriptor)
        && !superTypeDescriptor.isNative()) {
      problems.error(
          type.getSourcePosition(),
          "Native JsType '%s' can only extend native JsType classes.",
          readableDescription);
    }
    for (TypeDescriptor interfaceType : type.getSuperInterfaceTypeDescriptors()) {
      if (!interfaceType.isNative()) {
        problems.error(
            type.getSourcePosition(),
            "Native JsType '%s' can only %s native JsType interfaces.",
            readableDescription,
            type.isInterface() ? "extend" : "implement");
      }
    }

    if (type.hasInstanceInitializerBlocks()) {
      problems.error(
          type.getSourcePosition(),
          "Native JsType '%s' cannot have an instance initializer.",
          type.getDeclaration().getReadableDescription());
    }
    return true;
  }

  private void checkMemberOfNativeJsType(Member member) {
    MemberDescriptor memberDescriptor = member.getDescriptor();
    if (memberDescriptor.isJsOverlay() || memberDescriptor.isSynthetic()) {
      return;
    }

    String readableDescription = member.getReadableDescription();
    JsMemberType jsMemberType = memberDescriptor.getJsInfo().getJsMemberType();
    switch (jsMemberType) {
      case CONSTRUCTOR:
        if (!((Method) member).isEmpty()) {
          problems.error(
              member.getSourcePosition(),
              "Native JsType constructor '%s' cannot have non-empty method body.",
              readableDescription);
        }
        break;
      case METHOD:
      case GETTER:
      case SETTER:
        if (!member.isAbstract() && !member.isNative()) {
          problems.error(
              member.getSourcePosition(),
              "Native JsType method '%s' should be native, abstract or JsOverlay.",
              readableDescription);
        }
        break;
      case PROPERTY:
        Field field = (Field) member;
        if (field.getDescriptor().isFinal()) {
          problems.error(
              field.getSourcePosition(),
              "Native JsType field '%s' cannot be final.",
              member.getReadableDescription());
        } else if (field.hasInitializer()) {
          problems.error(
              field.getSourcePosition(),
              "Native JsType field '%s' cannot have initializer.",
              readableDescription);
        }
        break;
      case NONE:
        problems.error(
            member.getSourcePosition(),
            "Native JsType member '%s' cannot have @JsIgnore.",
            readableDescription);
        break;
      case UNDEFINED_ACCESSOR:
        // Nothing to check here. An error will be emitted for UNDEFINED_ACCESSOR elsewhere.
        break;
    }
  }

  private void checkJsFunction(Type type) {
    String readableDescription = type.getDeclaration().getReadableDescription();
    if (!type.getDeclaration().isFunctionalInterface()) {
      problems.error(
          type.getSourcePosition(),
          "JsFunction '%s' has to be a functional interface.",
          readableDescription);
      return;
    }

    if (!type.getSuperInterfaceTypeDescriptors().isEmpty()) {
      problems.error(
          type.getSourcePosition(),
          "JsFunction '%s' cannot extend other interfaces.",
          readableDescription);
    }

    if (type.getDeclaration().isJsType()) {
      problems.error(
          type.getSourcePosition(),
          "'%s' cannot be both a JsFunction and a JsType at the same time.",
          readableDescription);
    }

    for (Member member : type.getMembers()) {
      checkMemberOfJsFunction(member);
    }
  }

  private void checkMemberOfJsFunction(Member member) {
    MemberDescriptor memberDescriptor = member.getDescriptor();
    String messagePrefix = "JsFunction interface";

    if (memberDescriptor.isSynthetic()) {
      return;
    }

    if (!checkNotJsMember(member, messagePrefix)) {
      return;
    }

    if (memberDescriptor.isJsFunction()) {
      return;
    }

    checkMustBeJsOverlay(member, messagePrefix);
  }

  private void checkMustBeJsOverlay(Member member, String messagePrefix) {
    MemberDescriptor memberDescriptor = member.getDescriptor();
    if (memberDescriptor.isJsOverlay()) {
      return;
    }

    problems.error(
        member.getSourcePosition(),
        messagePrefix + " '%s' cannot declare non-JsOverlay member '%s'.",
        memberDescriptor.getEnclosingTypeDescriptor().getTypeDeclaration().getReadableDescription(),
        member.getReadableDescription());
  }

  private void checkJsFunctionImplementation(Type type) {
    TypeDeclaration typeDeclaration = type.getDeclaration();
    String readableDescription = typeDeclaration.getReadableDescription();
    if (!typeDeclaration.isFinal() && !typeDeclaration.isAnonymous()) {
      problems.error(
          type.getSourcePosition(),
          "JsFunction implementation '%s' must be final.",
          readableDescription);
    }

    if (typeDeclaration.isJsType()) {
      problems.error(
          type.getSourcePosition(),
          "'%s' cannot be both a JsFunction implementation and a JsType at the same time.",
          readableDescription);
    }

    if (type.getSuperInterfaceTypeDescriptors().size() != 1) {
      problems.error(
          type.getSourcePosition(),
          "JsFunction implementation '%s' cannot implement more than one interface.",
          readableDescription);
      return;
    }

    if (!TypeDescriptors.isJavaLangObject(type.getSuperTypeDescriptor())) {
      problems.error(
          type.getSourcePosition(),
          "JsFunction implementation '%s' cannot extend a class.",
          readableDescription);
      return;
    }

    for (Member member : type.getMembers()) {
      checkMemberOfJsFunctionImplementation(member);
    }
  }

  private void checkMemberOfJsFunctionImplementation(Member member) {
    if (member.getDescriptor().isSynthetic()) {
      return;
    }

    checkImplementableStatically(member, "JsFunction implementation");
  }

  private void checkImplementableStatically(Member member, String messagePrefix) {
    MemberDescriptor memberDescriptor = member.getDescriptor();

    if (member.isMethod()) {
      Method method = (Method) member;
      boolean hasNonJsFunctionOverride =
          method.getDescriptor().getOverriddenMethodDescriptors().stream()
              .anyMatch(Predicates.not(MethodDescriptor::isJsFunction));

      if (hasNonJsFunctionOverride) {
        // Methods that are not effectively static dispatch are disallowed.
        problems.error(
            member.getSourcePosition(),
            messagePrefix + " method '%s' cannot override a supertype method.",
            memberDescriptor.getReadableDescription());
        return;
      }

      if (method.isNative()) {
        // Only perform this check for methods to avoid giving error on fields that are not
        // explicitly marked native.
        problems.error(
            method.getSourcePosition(),
            messagePrefix + " method '%s' cannot be native.",
            method.getReadableDescription());
        return;
      }

      method.accept(
          new AbstractVisitor() {
            @Override
            public void exitSuperReference(SuperReference superReference) {
              problems.error(
                  method.getSourcePosition(),
                  "Cannot use 'super' in %s method '%s'.",
                  messagePrefix,
                  method.getReadableDescription());
            }
          });
    }

    checkNotJsMember(member, messagePrefix);
  }

  private boolean checkNotJsMember(Member member, String messagePrefix) {
    if (!member.isInitializerBlock() && member.getDescriptor().isJsMember()) {
      problems.error(
          member.getSourcePosition(),
          messagePrefix + " member '%s' cannot be JsMethod nor JsProperty nor JsConstructor.",
          member.getReadableDescription());
      return false;
    }
    return true;
  }

  private void checkJsFunctionSubtype(Type type) {
    for (TypeDescriptor superInterface : type.getSuperInterfaceTypeDescriptors()) {
      if (superInterface.isJsFunctionInterface()) {
        problems.error(
            type.getSourcePosition(),
            "'%s' cannot extend JsFunction '%s'.",
            type.getDeclaration().getReadableDescription(),
            superInterface.getReadableDescription());
      }
    }
  }

  private <T extends HasJsNameInfo & HasSourcePosition & HasReadableDescription>
      boolean checkJsName(T item) {
    String jsName = item.getSimpleJsName();
    if (jsName == null || JsUtils.isValidJsIdentifier(jsName)) {
      return true;
    }
    if (item.isNative() && JsUtils.isValidJsQualifiedName(jsName)) {
      return true;
    }

    errorInvalidName(jsName, "name", item);
    return false;
  }

  private <T extends HasJsNameInfo & HasSourcePosition & HasReadableDescription>
      boolean checkJsNamespace(T item) {
    String jsNamespace = item.getJsNamespace();
    if (jsNamespace == null
        || JsUtils.isGlobal(jsNamespace)
        || JsUtils.isValidJsQualifiedName(jsNamespace)) {
      return true;
    }

    errorInvalidName(jsNamespace, "namespace", item);
    return false;
  }

  private <T extends HasJsNameInfo & HasSourcePosition & HasReadableDescription>
      void errorInvalidName(String name, String nameType, T item) {
    if (name.isEmpty()) {
      problems.error(
          item.getSourcePosition(),
          "'%s' cannot have an empty %s.",
          item.getReadableDescription(),
          nameType);
    } else {
      problems.error(
          item.getSourcePosition(),
          "'%s' has invalid %s '%s'.",
          item.getReadableDescription(),
          nameType,
          name);
    }
  }

  private void checkMethodParameters(Method method) {
    // TODO(rluble): When overriding is included in the AST representation, add the relevant checks,
    // i.e. that a parameter can not change from optional into non optional in an override.
    boolean hasOptionalParameters = false;
    MethodDescriptor methodDescriptor = method.getDescriptor();

    int numberOfParameters = method.getParameters().size();
    Variable varargsParameter = method.getJsVarargsParameter();
    for (int i = 0; i < numberOfParameters; i++) {
      Variable parameter = method.getParameters().get(i);
      ParameterDescriptor parameterDescriptor = methodDescriptor.getParameterDescriptors().get(i);
      if (parameterDescriptor.isJsOptional()) {
        if (parameterDescriptor.getTypeDescriptor().isPrimitive()) {
          problems.error(
              method.getSourcePosition(),
              "JsOptional parameter '%s' in method '%s' cannot be of a primitive type.",
              parameter.getName(),
              method.getReadableDescription());
        }
        if (parameter == varargsParameter) {
          problems.error(
              method.getSourcePosition(),
              "JsOptional parameter '%s' in method '%s' cannot be a varargs parameter.",
              parameter.getName(),
              method.getReadableDescription());
        }
        hasOptionalParameters = true;
        continue;
      }
      if (hasOptionalParameters && parameter != varargsParameter) {
        problems.error(
            method.getSourcePosition(),
            "JsOptional parameter '%s' in method '%s' cannot precede parameters that are not "
                + "JsOptional.",
            method.getParameters().get(i - 1).getName(),
            method.getReadableDescription());
        break;
      }
    }
    if (hasOptionalParameters
        && !methodDescriptor.isJsMethod()
        && !methodDescriptor.isJsConstructor()
        && !methodDescriptor.isJsFunction()) {
      problems.error(
          method.getSourcePosition(),
          "JsOptional parameter in '%s' can only be declared in a JsMethod, a JsConstructor or a "
              + "JsFunction.",
          method.getReadableDescription());
    }

    // Check that parameters that are declared JsOptional in overridden methods remain JsOptional.
    for (MethodDescriptor overriddenMethodDescriptor :
        methodDescriptor.getOverriddenMethodDescriptors()) {
      for (int i = 0; i < overriddenMethodDescriptor.getParameterDescriptors().size(); i++) {
        if (!overriddenMethodDescriptor.isParameterOptional(i)) {
          continue;
        }
        if (!methodDescriptor.isParameterOptional(i)) {
          problems.error(
              method.getSourcePosition(),
              "Method '%s' should declare parameter '%s' as JsOptional",
              method.getReadableDescription(),
              method.getParameters().get(i).getName());
          return;
        }
      }
    }
  }

  private boolean checkJsConstructors(Type type) {
    if (type.isNative()) {
      return true;
    }

    if (!type.getDeclaration().hasJsConstructor()) {
      return true;
    }

    List<MethodDescriptor> jsConstructorDescriptors =
        type.getDeclaration().getJsConstructorMethodDescriptors();
    if (jsConstructorDescriptors.size() > 1) {
      problems.error(
          type.getSourcePosition(),
          "More than one JsConstructor exists for '%s'.",
          type.getReadableDescription());
      return false;
    }

    MethodDescriptor jsConstructorDescriptor = jsConstructorDescriptors.get(0);
    MethodDescriptor primaryConstructorDescriptor = getPrimaryConstructorDescriptor(type);
    if (primaryConstructorDescriptor != jsConstructorDescriptor) {
      problems.error(
          type.getSourcePosition(),
          "JsConstructor '%s' can be a JsConstructor only if all other constructors in the class "
              + "delegate to it.",
          jsConstructorDescriptor.getReadableDescription());
      return false;
    }

    // TODO(b/129550499): Remove this check once NormalizeConstructors is fixed to handle arbitrary
    // constructor delegation chains for JsConstructor classes.
    for (Method constructor : type.getConstructors()) {
      if (constructor.getDescriptor().isJsConstructor()) {
        continue;
      }
      MethodDescriptor delegatedConstructor =
          AstUtils.getConstructorInvocation(constructor).getTarget();
      if (delegatedConstructor == null || !delegatedConstructor.isJsConstructor()) {
        problems.error(
            type.getSourcePosition(),
            "Constructor '%s' should delegate to the JsConstructor '%s'. (b/129550499)",
            constructor.getReadableDescription(),
            jsConstructorDescriptor.getReadableDescription());
        return false;
      }
    }

    return true;
  }

  private static MethodDescriptor getPrimaryConstructorDescriptor(final Type type) {
    if (type.getConstructors().isEmpty()) {
      return type.getDeclaration()
          .getDeclaredMethodDescriptors()
          .stream()
          .filter(MethodDescriptor::isConstructor)
          .collect(MoreCollectors.onlyElement());
    }

    ImmutableList<Method> superDelegatingConstructors =
        type.getConstructors()
            .stream()
            .filter(Predicates.not(AstUtils::hasThisCall))
            .collect(ImmutableList.toImmutableList());
    return superDelegatingConstructors.size() != 1
        ? null
        : superDelegatingConstructors.get(0).getDescriptor();
  }

  private void checkJsConstructorSubtype(Type type) {
    if (type.isNative()) {
      return;
    }

    if (!type.getDeclaration().isJsConstructorSubtype()) {
      return;
    }

    if (!type.getDeclaration().hasJsConstructor()) {
      problems.error(
          type.getSourcePosition(),
          "Class '%s' should have a JsConstructor.",
          type.getReadableDescription());
      return;
    }

    List<MethodDescriptor> superJsConstructorMethodDescriptors =
        type.getSuperTypeDescriptor().getJsConstructorMethodDescriptors();

    Method jsConstructor = getJsConstructor(type);
    if (jsConstructor == null) {
      // The JsConstructor is the implicit constructor and delegates to the default constructor
      // for the super class.
      MethodDescriptor implicitJsConstructorDescriptor =
          type.getDeclaration().getJsConstructorMethodDescriptors().get(0);
      if (!type.getSuperTypeDescriptor()
          .getDefaultConstructorMethodDescriptor()
          .isJsConstructor()) {
        problems.error(
            type.getSourcePosition(),
            "Implicit JsConstructor '%s' can only delegate to super JsConstructor '%s'.",
            implicitJsConstructorDescriptor.getReadableDescription(),
            superJsConstructorMethodDescriptors.get(0).getReadableDescription());
      }
      return;
    }

    MethodDescriptor delegatedSuperConstructor =
        AstUtils.getDelegatedSuperConstructorDescriptor(jsConstructor);
    if (!delegatedSuperConstructor.isJsConstructor()) {
      problems.error(
          jsConstructor.getSourcePosition(),
          "JsConstructor '%s' can only delegate to super JsConstructor '%s'.",
          jsConstructor.getDescriptor().getReadableDescription(),
          superJsConstructorMethodDescriptors.get(0).getReadableDescription());
    }
  }

  private static Method getJsConstructor(Type type) {
    return type.getConstructors()
        .stream()
        .filter(constructor -> constructor.getDescriptor().isJsConstructor())
        .findFirst()
        .orElse(null);
  }

  private boolean checkJsPropertyAccessor(Method method) {
    MethodDescriptor methodDescriptor = method.getDescriptor();
    JsMemberType memberType = methodDescriptor.getJsInfo().getJsMemberType();

    if (methodDescriptor.getSimpleJsName() == null) {
      checkArgument(memberType.isPropertyAccessor());
      problems.error(
          method.getSourcePosition(),
          "JsProperty '%s' should either follow Java Bean naming conventions or provide a name.",
          method.getReadableDescription());
      return false;
    }

    switch (memberType) {
      case UNDEFINED_ACCESSOR:
        problems.error(
            method.getSourcePosition(),
            "JsProperty '%s' should have a correct setter or getter signature.",
            method.getReadableDescription());
        break;
      case GETTER:
        TypeDescriptor returnTypeDescriptor = methodDescriptor.getReturnTypeDescriptor();
        if (methodDescriptor.getName().startsWith("is")
            && !TypeDescriptors.isPrimitiveBoolean(returnTypeDescriptor)) {
          problems.error(
              method.getSourcePosition(),
              "JsProperty '%s' cannot have a non-boolean return.",
              method.getReadableDescription());
        }
        break;
      case SETTER:
        if (methodDescriptor.isVarargs()) {
          problems.error(
              method.getSourcePosition(),
              "JsProperty '%s' cannot have a vararg parameter.",
              method.getReadableDescription());
        }
        break;
      default:
        break;
    }

    return true;
  }

  private boolean checkJsPropertyConsistency(
      SourcePosition sourcePosition, MethodDescriptor thisMember, MethodDescriptor thatMember) {
    MethodDescriptor setter = thisMember.isJsPropertySetter() ? thisMember : thatMember;
    MethodDescriptor getter = thisMember.isJsPropertyGetter() ? thisMember : thatMember;

    List<TypeDescriptor> setterParams = setter.getParameterTypeDescriptors();
    if (!getter.getReturnTypeDescriptor().isSameBaseType(setterParams.get(0))) {
      problems.error(
          sourcePosition,
          "JsProperty setter '%s' and getter '%s' cannot have inconsistent types.",
          setter.getReadableDescription(),
          getter.getReadableDescription());
      return false;
    }
    return true;
  }

  private void checkNameCollisions(
      Multimap<String, MemberDescriptor> jsMembersByName, Member member) {
    checkOverrideConsistency(member);
    if (member.isNative()) {
      return;
    }

    String name = member.getDescriptor().getSimpleJsName();

    Set<MemberDescriptor> potentiallyCollidingMembers =
        new LinkedHashSet<>(jsMembersByName.get(name));

    // Remove self.
    boolean removed = potentiallyCollidingMembers.removeIf(member.getDescriptor()::isSameMember);
    checkState(removed);

    // Remove native members.
    potentiallyCollidingMembers.removeIf(MemberDescriptor::isNative);

    if (potentiallyCollidingMembers.isEmpty()) {
      // No conflicting members, proceed.
      return;
    }

    MemberDescriptor potentiallyCollidingMember = potentiallyCollidingMembers.iterator().next();
    if (potentiallyCollidingMembers.size() == 1
        && isJsPropertyAccessorPair(member.getDescriptor(), potentiallyCollidingMember)) {
      if (!checkJsPropertyConsistency(
          member.getSourcePosition(),
          (MethodDescriptor) member.getDescriptor(),
          (MethodDescriptor) potentiallyCollidingMember)) {
        // remove colliding method, to avoid duplicate error messages.
        jsMembersByName.get(name).removeIf(member.getDescriptor()::isSameMember);
      }
      return;
    }

    problems.error(
        member.getSourcePosition(),
        "'%s' and '%s' cannot both use the same JavaScript name '%s'.",
        member.getDescriptor().getReadableDescription(),
        potentiallyCollidingMember.getReadableDescription(),
        name);

    // remove colliding method, to avoid duplicate error messages.
    jsMembersByName.get(name).removeIf(member.getDescriptor()::isSameMember);
  }

  private static boolean isJsPropertyAccessorPair(
      MemberDescriptor thisMember, MemberDescriptor thatMember) {
    return (thisMember.isJsPropertyGetter() && thatMember.isJsPropertySetter())
        || (thatMember.isJsPropertyGetter() && thisMember.isJsPropertySetter());
  }

  private static boolean isInstanceJsMember(MemberDescriptor memberDescriptor) {
    return !(memberDescriptor.isStatic() || memberDescriptor.isConstructor())
        && memberDescriptor.isJsMember()
        && !memberDescriptor.isSynthetic();
  }

  private static boolean isStaticJsMember(MemberDescriptor memberDescriptor) {
    // Constructors are checked specifically to give a better error message.
    return memberDescriptor.isStatic()
        && memberDescriptor.isJsMember()
        && !memberDescriptor.isSynthetic();
  }

  private static Multimap<String, MemberDescriptor> collectInstanceNames(
      DeclaredTypeDescriptor typeDescriptor) {
    if (typeDescriptor == null) {
      return LinkedHashMultimap.create();
    }

    // The supertype of an interface is java.lang.Object. java.lang.Object methods need to be
    // considered when checking for name collisions.
    // TODO(b/135140069): remove if the model starts including java.lang.Object as the supertype of
    // interfaces.
    DeclaredTypeDescriptor superTypeDescriptor =
        typeDescriptor.isInterface() && !typeDescriptor.isNative()
            ? TypeDescriptors.get().javaLangObject
            : typeDescriptor.getSuperTypeDescriptor();
    Multimap<String, MemberDescriptor> instanceMembersByName =
        collectInstanceNames(superTypeDescriptor);
    for (MemberDescriptor member : typeDescriptor.getDeclaredMemberDescriptors()) {
      if (isInstanceJsMember(member)) {
        addMember(instanceMembersByName, member);
      }
    }
    return instanceMembersByName;
  }

  private static Multimap<String, MemberDescriptor> collectStaticNames(
      DeclaredTypeDescriptor typeDescriptor) {
    Multimap<String, MemberDescriptor> staticMembersByName = LinkedHashMultimap.create();
    for (MemberDescriptor member : typeDescriptor.getDeclaredMemberDescriptors()) {
      if (isStaticJsMember(member)) {
        addMember(staticMembersByName, member);
      }
    }
    return staticMembersByName;
  }

  private static void addMember(
      Multimap<String, MemberDescriptor> memberByMemberName, MemberDescriptor member) {
    String name = member.getSimpleJsName();
    Iterables.removeIf(memberByMemberName.get(name), m -> overrides(member, m));
    memberByMemberName.put(name, member);
  }

  private static boolean overrides(
      MemberDescriptor member, MemberDescriptor potentiallyOverriddenMember) {
    if (!member.isMethod() || !potentiallyOverriddenMember.isMethod()) {
      return false;
    }

    MethodDescriptor method = (MethodDescriptor) member.getDeclarationDescriptor();
    MethodDescriptor potentiallyOverriddenMethod = (MethodDescriptor) potentiallyOverriddenMember;
    return method.isOverride(potentiallyOverriddenMethod)
        || method.isOverride(potentiallyOverriddenMethod.getDeclarationDescriptor());
  }

  private void checkUnusableByJs(Member member) {
    if (isUnusableByJsSuppressed(member.getDescriptor())) {
      return;
    }

    if (member.isField()) {
      FieldDescriptor fieldDescriptor = (FieldDescriptor) member.getDescriptor();
      TypeDescriptor fieldTypeDescriptor = fieldDescriptor.getTypeDescriptor();
      warnIfUnusableByJs(
          fieldTypeDescriptor,
          String.format("Type '%s' of field", fieldTypeDescriptor.getReadableDescription()),
          member);
    }

    if (member.isMethod()) {
      Method method = (Method) member;
      MethodDescriptor methodDescriptor = method.getDescriptor();
      warnIfUnusableByJs(methodDescriptor, "Method", member);

      TypeDescriptor returnTypeDescriptor = methodDescriptor.getReturnTypeDescriptor();
      warnIfUnusableByJs(returnTypeDescriptor, "Return type of", member);

      Variable varargsParameter = method.getJsVarargsParameter();
      for (Variable parameter : method.getParameters()) {
        if (!parameter.isUnusableByJsSuppressed()) {
          TypeDescriptor parameterTypeDescriptor =
              parameter == varargsParameter
                  ? ((ArrayTypeDescriptor) parameter.getTypeDescriptor())
                      .getComponentTypeDescriptor()
                  : parameter.getTypeDescriptor();
          String prefix = String.format("Type of parameter '%s' in", parameter.getName());
          warnIfUnusableByJs(parameterTypeDescriptor, prefix, member);
        }
      }
    }
  }

  private static boolean isUnusableByJsSuppressed(MemberDescriptor memberDescriptor) {
    // TODO(b/36227943): Abide by standard rules regarding suppression annotations in
    // enclosing elements.
    return memberDescriptor.isUnusableByJsSuppressed()
        || isUnusableByJsSuppressed(memberDescriptor.getEnclosingTypeDescriptor());
  }

  private static boolean isUnusableByJsSuppressed(DeclaredTypeDescriptor typeDescriptor) {
    // TODO(b/36227943): Abide by standard rules regarding suppression annotations in
    // enclosing elements.
    if (typeDescriptor.isUnusableByJsSuppressed()) {
      return true;
    }

    DeclaredTypeDescriptor enclosingTypeDescriptor = typeDescriptor.getEnclosingTypeDescriptor();
    return enclosingTypeDescriptor != null && isUnusableByJsSuppressed(enclosingTypeDescriptor);
  }

  private void warnIfUnusableByJs(TypeDescriptor typeDescriptor, String prefix, Member member) {
    if (typeDescriptor.canBeReferencedExternally()) {
      return;
    }

    warnUnusableByJs(prefix, member);
  }

  private void warnIfUnusableByJs(MemberDescriptor memberDescriptor, String prefix, Member member) {
    if (memberDescriptor.canBeReferencedExternally()) {
      return;
    }

    warnUnusableByJs(prefix, member);
  }

  private void warnUnusableByJs(String prefix, Member member) {
    // TODO(b/36362935): consider [unusable-by-js] (suppressible) errors instead of warnings.
    problems.warning(
        member.getSourcePosition(),
        "[unusable-by-js] %s '%s' is not usable by but exposed to JavaScript.",
        prefix,
        member.getReadableDescription());
    wasUnusableByJsWarningReported = true;
  }
}
