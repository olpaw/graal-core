/*
 * Copyright (c) 2013, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.compiler.aarch64;

import static com.oracle.graal.lir.LIRValueUtil.asJavaConstant;
import static com.oracle.graal.lir.LIRValueUtil.isJavaConstant;

import java.util.function.Function;

import com.oracle.graal.asm.NumUtil;
import com.oracle.graal.asm.aarch64.AArch64Address.AddressingMode;
import com.oracle.graal.asm.aarch64.AArch64Assembler.ConditionFlag;
import com.oracle.graal.asm.aarch64.AArch64Assembler.ExtendType;
import com.oracle.graal.compiler.common.calc.Condition;
import com.oracle.graal.compiler.common.spi.LIRKindTool;
import com.oracle.graal.lir.ConstantValue;
import com.oracle.graal.lir.LIRFrameState;
import com.oracle.graal.lir.LIRValueUtil;
import com.oracle.graal.lir.LabelRef;
import com.oracle.graal.lir.StandardOp;
import com.oracle.graal.lir.SwitchStrategy;
import com.oracle.graal.lir.Variable;
import com.oracle.graal.lir.aarch64.AArch64AddressValue;
import com.oracle.graal.lir.aarch64.AArch64ArithmeticOp;
import com.oracle.graal.lir.aarch64.AArch64Compare;
import com.oracle.graal.lir.aarch64.AArch64ControlFlow;
import com.oracle.graal.lir.aarch64.AArch64ControlFlow.BranchOp;
import com.oracle.graal.lir.aarch64.AArch64ControlFlow.CondMoveOp;
import com.oracle.graal.lir.aarch64.AArch64ControlFlow.StrategySwitchOp;
import com.oracle.graal.lir.aarch64.AArch64Move;
import com.oracle.graal.lir.aarch64.AArch64Move.CompareAndSwap;
import com.oracle.graal.lir.aarch64.AArch64Move.MembarOp;
import com.oracle.graal.lir.aarch64.AArch64PauseOp;
import com.oracle.graal.lir.gen.LIRGenerationResult;
import com.oracle.graal.lir.gen.LIRGenerator;
import com.oracle.graal.phases.util.Providers;

import jdk.vm.ci.aarch64.AArch64Kind;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.common.JVMCIError;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.LIRKind;
import jdk.vm.ci.meta.PlatformKind;
import jdk.vm.ci.meta.Value;

public abstract class AArch64LIRGenerator extends LIRGenerator {

    public AArch64LIRGenerator(LIRKindTool lirKindTool, AArch64ArithmeticLIRGenerator arithmeticLIRGen, MoveFactory moveFactory, Providers providers, LIRGenerationResult lirGenRes) {
        super(lirKindTool, arithmeticLIRGen, moveFactory, providers, lirGenRes);
    }

    /**
     * Checks whether the supplied constant can be used without loading it into a register for store
     * operations, i.e., on the right hand side of a memory access.
     *
     * @param c The constant to check.
     * @return True if the constant can be used directly, false if the constant needs to be in a
     *         register.
     */
    protected static final boolean canStoreConstant(JavaConstant c) {
        // Our own code never calls this since we can't make a definite statement about whether or
        // not we can inline a constant without knowing what kind of operation we execute. Let's be
        // optimistic here and fix up mistakes later.
        return true;
    }

    /**
     * AArch64 cannot use anything smaller than a word in any instruction other than load and store.
     */
    @Override
    public LIRKind toRegisterKind(LIRKind kind) {
        switch ((AArch64Kind) kind.getPlatformKind()) {
            case BYTE:
            case WORD:
                return kind.changeType(AArch64Kind.DWORD);
            default:
                return kind;
        }
    }

    @Override
    public void emitNullCheck(Value address, LIRFrameState state) {
        append(new AArch64Move.NullCheckOp(asAddressValue(address), state));
    }

    @Override
    public Variable emitAddress(AllocatableValue stackslot) {
        Variable result = newVariable(LIRKind.value(target().arch.getWordKind()));
        append(new AArch64Move.StackLoadAddressOp(result, stackslot));
        return result;
    }

    public AArch64AddressValue asAddressValue(Value address) {
        if (address instanceof AArch64AddressValue) {
            return (AArch64AddressValue) address;
        } else {
            return new AArch64AddressValue(address.getLIRKind(), asAllocatable(address), Value.ILLEGAL, 0, false, AddressingMode.BASE_REGISTER_ONLY);
        }
    }

    @Override
    public Variable emitCompareAndSwap(Value address, Value expectedValue, Value newValue, Value trueValue, Value falseValue) {
        AArch64AddressValue addressValue = convertToBaseRegisterOnlyAddress(asAddressValue(address));
        Variable result = newVariable(trueValue.getLIRKind());
        Variable scratch = newVariable(LIRKind.value(AArch64Kind.WORD));
        append(new CompareAndSwap(result, loadNonCompareConst(expectedValue), loadReg(newValue), addressValue, scratch));
        return result;
    }

    /**
     * Converts an arbitrary address to a BASE_REGISTER_ONLY form. This is useful since several
     * instructions (e.g. load-acquire/store-release) are limited to this addressing mode.
     *
     * @return An address using the
     *         {@link com.oracle.graal.asm.aarch64.AArch64Address.AddressingMode#BASE_REGISTER_ONLY}
     *         addressingmode, pointing to the same location as address.
     */
    private AArch64AddressValue convertToBaseRegisterOnlyAddress(AArch64AddressValue address) {
        AllocatableValue base = address.getBase();
        AllocatableValue index = address.getOffset();
        int immediate = address.getImmediate();
        int shiftAmt;
        if (address.isScaled()) {
            shiftAmt = NumUtil.log2Ceil(address.getPlatformKind().getSizeInBytes() * Byte.SIZE);
        } else {
            shiftAmt = 0;
        }
        switch (address.getAddressingMode()) {
            case IMMEDIATE_SCALED:
            case IMMEDIATE_UNSCALED:
                JavaConstant constVal = JavaConstant.forInt(immediate << shiftAmt);
                ConstantValue constValue = new ConstantValue(LIRKind.value(AArch64Kind.WORD), constVal);
                base = asAllocatable(getArithmetic().emitAdd(base, constValue, false));
                break;
            case REGISTER_OFFSET:
                append(new AArch64ArithmeticOp.ExtendedAddShiftOp(base, base, index, ExtendType.UXTX, shiftAmt));
                break;
            case EXTENDED_REGISTER_OFFSET:
                append(new AArch64ArithmeticOp.ExtendedAddShiftOp(base, base, index, ExtendType.SXTW, shiftAmt));
                break;
            case BASE_REGISTER_ONLY:
                // nothing to do.
                break;
            default:
                throw JVMCIError.shouldNotReachHere();
        }
        return new AArch64AddressValue(address.getLIRKind(), base, Value.ILLEGAL, 0, false, AddressingMode.BASE_REGISTER_ONLY);
    }

    @Override
    public void emitData(AllocatableValue dst, byte[] data) {
        append(new AArch64Move.LoadDataOp(dst, data));
    }

    @Override
    public void emitMembar(int barriers) {
        int necessaryBarriers = target().arch.requiredBarriers(barriers);
        if (target().isMP && necessaryBarriers != 0) {
            append(new MembarOp(necessaryBarriers));
        }
    }

    @Override
    public void emitJump(LabelRef label) {
        assert label != null;
        append(new StandardOp.JumpOp(label));
    }

    @Override
    public void emitOverflowCheckBranch(LabelRef overflow, LabelRef noOverflow, LIRKind cmpKind, double overflowProbability) {
        append(new AArch64ControlFlow.BranchOp(ConditionFlag.VS, overflow, noOverflow, overflowProbability));
    }

    /**
     * Branches to label if (left & right) == 0. If negated is true branchse on non-zero instead.
     *
     * @param left Integer kind. Non null.
     * @param right Integer kind. Non null.
     * @param trueDestination destination if left & right == 0. Non null.
     * @param falseDestination destination if left & right != 0. Non null
     * @param trueSuccessorProbability hoistoric probability that comparison is true
     */
    @Override
    public void emitIntegerTestBranch(Value left, Value right, LabelRef trueDestination, LabelRef falseDestination, double trueSuccessorProbability) {
        assert ((AArch64Kind) left.getPlatformKind()).isInteger() && left.getPlatformKind() == right.getPlatformKind();
        ((AArch64ArithmeticLIRGenerator) getArithmetic()).emitBinary(LIRKind.combine(left, right), AArch64ArithmeticOp.ANDS, true, left, right);
        append(new AArch64ControlFlow.BranchOp(ConditionFlag.EQ, trueDestination, falseDestination, trueSuccessorProbability));
    }

    /**
     * Conditionally move trueValue into new variable if cond + unorderedIsTrue is true, else
     * falseValue.
     *
     * @param left Arbitrary value. Has to have same type as right. Non null.
     * @param right Arbitrary value. Has to have same type as left. Non null.
     * @param cond condition that decides whether to move trueValue or falseValue into result. Non
     *            null.
     * @param unorderedIsTrue defines whether floating-point comparisons consider unordered true or
     *            not. Ignored for integer comparisons.
     * @param trueValue arbitrary value same type as falseValue. Non null.
     * @param falseValue arbitrary value same type as trueValue. Non null.
     * @return value containing trueValue if cond + unorderedIsTrue is true, else falseValue. Non
     *         null.
     */
    @Override
    public Variable emitConditionalMove(PlatformKind cmpKind, Value left, Value right, Condition cond, boolean unorderedIsTrue, Value trueValue, Value falseValue) {
        boolean mirrored = emitCompare(cmpKind, left, right, cond, unorderedIsTrue);
        Condition finalCondition = mirrored ? cond.mirror() : cond;
        boolean finalUnorderedIsTrue = mirrored ? !unorderedIsTrue : unorderedIsTrue;
        ConditionFlag cmpCondition = toConditionFlag(((AArch64Kind) cmpKind).isInteger(), finalCondition, finalUnorderedIsTrue);
        Variable result = newVariable(trueValue.getLIRKind());
        append(new CondMoveOp(result, cmpCondition, loadReg(trueValue), loadReg(falseValue)));
        return result;
    }

    @Override
    public void emitCompareBranch(PlatformKind cmpKind, Value left, Value right, Condition cond, boolean unorderedIsTrue, LabelRef trueDestination, LabelRef falseDestination,
                    double trueDestinationProbability) {
        boolean mirrored = emitCompare(cmpKind, left, right, cond, unorderedIsTrue);
        Condition finalCondition = mirrored ? cond.mirror() : cond;
        boolean finalUnorderedIsTrue = mirrored ? !unorderedIsTrue : unorderedIsTrue;
        ConditionFlag cmpCondition = toConditionFlag(((AArch64Kind) cmpKind).isInteger(), finalCondition, finalUnorderedIsTrue);
        append(new BranchOp(cmpCondition, trueDestination, falseDestination, trueDestinationProbability));
    }

    private static ConditionFlag toConditionFlag(boolean isInt, Condition cond, boolean unorderedIsTrue) {
        return isInt ? toIntConditionFlag(cond) : toFloatConditionFlag(cond, unorderedIsTrue);
    }

    /**
     * Takes a Condition and unorderedIsTrue flag and returns the correct Aarch64 specific
     * ConditionFlag. Note: This is only correct if the emitCompare code for floats has correctly
     * handled the case of 'EQ && unorderedIsTrue', respectively 'NE && !unorderedIsTrue'!
     */
    private static ConditionFlag toFloatConditionFlag(Condition cond, boolean unorderedIsTrue) {
        switch (cond) {
            case LT:
                return unorderedIsTrue ? ConditionFlag.LT : ConditionFlag.LO;
            case LE:
                return unorderedIsTrue ? ConditionFlag.LE : ConditionFlag.LS;
            case GE:
                return unorderedIsTrue ? ConditionFlag.PL : ConditionFlag.GE;
            case GT:
                return unorderedIsTrue ? ConditionFlag.HI : ConditionFlag.GT;
            case EQ:
                return ConditionFlag.EQ;
            case NE:
                return ConditionFlag.NE;
            default:
                throw JVMCIError.shouldNotReachHere();
        }
    }

    /**
     * Takes a Condition and returns the correct Aarch64 specific ConditionFlag.
     */
    private static ConditionFlag toIntConditionFlag(Condition cond) {
        switch (cond) {
            case EQ:
                return ConditionFlag.EQ;
            case NE:
                return ConditionFlag.NE;
            case LT:
                return ConditionFlag.LT;
            case LE:
                return ConditionFlag.LE;
            case GT:
                return ConditionFlag.GT;
            case GE:
                return ConditionFlag.GE;
            case AE:
                return ConditionFlag.HS;
            case BE:
                return ConditionFlag.LS;
            case AT:
                return ConditionFlag.HI;
            case BT:
                return ConditionFlag.LO;
            default:
                throw JVMCIError.shouldNotReachHere();
        }
    }

    /**
     * This method emits the compare instruction, and may reorder the operands. It returns true if
     * it did so.
     *
     * @param a the left operand of the comparison. Has to have same type as b. Non null.
     * @param b the right operand of the comparison. Has to have same type as a. Non null.
     * @return true if mirrored (i.e. "b cmp a" instead of "a cmp b" was done).
     */
    private boolean emitCompare(PlatformKind cmpKind, Value a, Value b, Condition condition, boolean unorderedIsTrue) {
        AllocatableValue left;
        Value right;
        boolean mirrored;
        AArch64Kind kind = (AArch64Kind) cmpKind;
        if (kind.isInteger()) {
            if (LIRValueUtil.isVariable(b) || b instanceof RegisterValue) {
                left = loadReg(b);
                right = loadNonConst(a);
                mirrored = true;
            } else {
                left = loadReg(a);
                right = loadNonConst(b);
                mirrored = false;
            }
            append(new AArch64Compare.CompareOp(left, asAllocatable(right)));
        } else {
            if (AArch64Compare.FloatCompareOp.isFloatCmpConstant(a, condition, unorderedIsTrue)) {
                left = loadReg(b);
                right = a;
                mirrored = true;
            } else if (AArch64Compare.FloatCompareOp.isFloatCmpConstant(b, condition, unorderedIsTrue)) {
                left = loadReg(a);
                right = b;
                mirrored = false;
            } else {
                left = loadReg(a);
                right = loadReg(b);
                mirrored = false;
            }
            append(new AArch64Compare.FloatCompareOp(left, asAllocatable(right), condition, unorderedIsTrue));
        }
        return mirrored;
    }

    /**
     * Checks whether value can be used directly with a gpCompare instruction. This is <b>not</b>
     * the same as {@link AArch64ArithmeticLIRGenerator#isArithmeticConstant(JavaConstant)}, because
     * 0.0 is a valid compare constant for floats, while there are no arithmetic constants for
     * floats.
     *
     * @param value any type. Non null.
     * @return true if value can be used directly in comparison instruction, false otherwise.
     */
    public boolean isCompareConstant(Value value) {
        if (!isJavaConstant(value)) {
            return false;
        }
        JavaConstant constant = asJavaConstant(value);
        if (((AArch64Kind) value.getPlatformKind()).isInteger()) {
            return AArch64ArithmeticLIRGenerator.isArithmeticConstant(constant);
        } else {
            return constant.isDefaultForKind();
        }
    }

    /**
     * Moves trueValue into result if (left & right) == 0, else falseValue.
     *
     * @param left Integer kind. Non null.
     * @param right Integer kind. Non null.
     * @param trueValue Integer kind. Non null.
     * @param falseValue Integer kind. Non null.
     * @return virtual register containing trueValue if (left & right) == 0, else falseValue.
     */
    @Override
    public Variable emitIntegerTestMove(Value left, Value right, Value trueValue, Value falseValue) {
        assert ((AArch64Kind) left.getPlatformKind()).isInteger() && ((AArch64Kind) right.getPlatformKind()).isInteger();
        assert ((AArch64Kind) trueValue.getPlatformKind()).isInteger() && ((AArch64Kind) falseValue.getPlatformKind()).isInteger();
        ((AArch64ArithmeticLIRGenerator) getArithmetic()).emitBinary(trueValue.getLIRKind(), AArch64ArithmeticOp.ANDS, true, left, right);
        Variable result = newVariable(trueValue.getLIRKind());
        append(new CondMoveOp(result, ConditionFlag.EQ, load(trueValue), load(falseValue)));
        return result;
    }

    @Override
    public void emitStrategySwitch(SwitchStrategy strategy, Variable key, LabelRef[] keyTargets, LabelRef defaultTarget) {
        append(createStrategySwitchOp(strategy, keyTargets, defaultTarget, key, newVariable(key.getLIRKind()), AArch64LIRGenerator::toIntConditionFlag));
    }

    protected StrategySwitchOp createStrategySwitchOp(SwitchStrategy strategy, LabelRef[] keyTargets, LabelRef defaultTarget, Variable key, AllocatableValue scratchValue,
                    Function<Condition, ConditionFlag> converter) {
        return new StrategySwitchOp(strategy, keyTargets, defaultTarget, key, scratchValue, converter);
    }

    @Override
    protected void emitTableSwitch(int lowKey, LabelRef defaultTarget, LabelRef[] targets, Value key) {
        // Make copy of key since the TableSwitch destroys its input.
        Variable tmp = emitMove(key);
        Variable scratch = newVariable(LIRKind.value(AArch64Kind.WORD));
        append(new AArch64ControlFlow.TableSwitchOp(lowKey, defaultTarget, targets, tmp, scratch));
    }

    @Override
    public Variable emitByteSwap(Value operand) {
        // TODO (das) Do not generate until we support vector instructions
        throw JVMCIError.unimplemented("Do not generate until we support vector instructions");
    }

    @Override
    public Variable emitArrayEquals(JavaKind kind, Value array1, Value array2, Value length) {
        // TODO (das) Do not generate until we support vector instructions
        throw JVMCIError.unimplemented("Do not generate until we support vector instructions");
    }

    @Override
    protected JavaConstant zapValueForKind(PlatformKind kind) {
        long dead = 0xDEADDEADDEADDEADL;
        switch ((AArch64Kind) kind) {
            case BYTE:
                return JavaConstant.forByte((byte) dead);
            case WORD:
                return JavaConstant.forShort((short) dead);
            case DWORD:
                return JavaConstant.forInt((int) dead);
            case QWORD:
                return JavaConstant.forLong(dead);
            case SINGLE:
                return JavaConstant.forFloat(Float.intBitsToFloat((int) dead));
            case DOUBLE:
                return JavaConstant.forDouble(Double.longBitsToDouble(dead));
            default:
                throw JVMCIError.shouldNotReachHere();
        }
    }

    /**
     * Loads value into virtual register. Contrary to {@link #load(Value)} this handles
     * RegisterValues (i.e. values corresponding to fixed physical registers) correctly, by not
     * creating an unnecessary move into a virtual register.
     *
     * This avoids generating the following code: mov x0, x19 # x19 is fixed thread register ldr x0,
     * [x0] instead of: ldr x0, [x19].
     */
    protected AllocatableValue loadReg(Value val) {
        if (!(val instanceof Variable || val instanceof RegisterValue)) {
            return emitMove(val);
        }
        return (AllocatableValue) val;
    }

    /**
     * If value is a constant that cannot be used directly with a gpCompare instruction load it into
     * a register and return the register, otherwise return constant value unchanged.
     */
    protected Value loadNonCompareConst(Value value) {
        if (!isCompareConstant(value)) {
            return loadReg(value);
        }
        return value;
    }

    @Override
    public void emitPause() {
        append(new AArch64PauseOp());
    }
}
