/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.builtins

import org.jetbrains.kotlin.builtins.functions.BuiltInFictitiousFunctionClassFactory
import org.jetbrains.kotlin.builtins.functions.FunctionClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.annotations.BuiltInAnnotationDescriptor
import org.jetbrains.kotlin.descriptors.annotations.FilteredAnnotations
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqNameUnsafe
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.constants.StringValue
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.typeUtil.asTypeProjection
import org.jetbrains.kotlin.types.typeUtil.replaceAnnotations
import org.jetbrains.kotlin.types.typeUtil.supertypes
import org.jetbrains.kotlin.utils.DFS
import org.jetbrains.kotlin.utils.addIfNotNull
import java.util.*

private fun KotlinType.isTypeOrSubtypeOf(predicate: (KotlinType) -> Boolean): Boolean =
        predicate(this) ||
        DFS.dfsFromNode(
                this,
                DFS.Neighbors { it.constructor.supertypes },
                DFS.VisitedWithSet(),
                object : DFS.AbstractNodeHandler<KotlinType, Boolean>() {
                    private var result = false

                    override fun beforeChildren(current: KotlinType): Boolean {
                        if (predicate(current)) {
                            result = true
                        }
                        return !result
                    }

                    override fun result() = result
                }
        )

val KotlinType.isFunctionTypeOrSubtype: Boolean
    get() = isTypeOrSubtypeOf { it.isFunctionType }

val KotlinType.isSuspendFunctionTypeOrSubtype: Boolean
    get() = isTypeOrSubtypeOf { it.isSuspendFunctionType }

val KotlinType.isBuiltinFunctionalTypeOrSubtype: Boolean
    get() = isTypeOrSubtypeOf { it.isBuiltinFunctionalType }

val KotlinType.isFunctionType: Boolean
    get() = constructor.declarationDescriptor?.getFunctionalClassKind() == FunctionClassDescriptor.Kind.Function

val KotlinType.isKFunctionType: Boolean
    get() = constructor.declarationDescriptor?.getFunctionalClassKind() == FunctionClassDescriptor.Kind.KFunction

val KotlinType.isSuspendFunctionType: Boolean
    get() = constructor.declarationDescriptor?.getFunctionalClassKind() == FunctionClassDescriptor.Kind.SuspendFunction

val KotlinType.isKSuspendFunctionType: Boolean
    get() = constructor.declarationDescriptor?.getFunctionalClassKind() == FunctionClassDescriptor.Kind.KSuspendFunction

val KotlinType.isFunctionOrSuspendFunctionType: Boolean
    get() = isFunctionType || isSuspendFunctionType

val KotlinType.isFunctionOrKFunctionTypeWithAnySuspendability: Boolean
    get() = isFunctionType || isSuspendFunctionType || isKFunctionType || isKSuspendFunctionType

val KotlinType.isBuiltinFunctionalType: Boolean
    get() = constructor.declarationDescriptor?.isBuiltinFunctionalClassDescriptor == true

val DeclarationDescriptor.isBuiltinFunctionalClassDescriptor: Boolean
    get() {
        val functionalClassKind = getFunctionalClassKind()
        return functionalClassKind == FunctionClassDescriptor.Kind.Function ||
                functionalClassKind == FunctionClassDescriptor.Kind.SuspendFunction
    }

fun isBuiltinFunctionClass(classId: ClassId): Boolean {
    if (!classId.startsWith(KotlinBuiltIns.BUILT_INS_PACKAGE_NAME)) return false

    val kind = classId.asSingleFqName().toUnsafe().getFunctionalClassKind()
    return kind == FunctionClassDescriptor.Kind.Function ||
           kind == FunctionClassDescriptor.Kind.SuspendFunction
}

val KotlinType.isNonExtensionFunctionType: Boolean
    get() = isFunctionType && !isTypeAnnotatedWithExtensionFunctionType

val KotlinType.isExtensionFunctionType: Boolean
    get() = isFunctionType && isTypeAnnotatedWithExtensionFunctionType

val KotlinType.isBuiltinExtensionFunctionalType: Boolean
    get() = isBuiltinFunctionalType && isTypeAnnotatedWithExtensionFunctionType

private val KotlinType.isTypeAnnotatedWithExtensionFunctionType: Boolean
    get() = annotations.findAnnotation(KotlinBuiltIns.FQ_NAMES.extensionFunctionType) != null

/**
 * @return true if this is an FQ name of a fictitious class representing the function type,
 * e.g. kotlin.Function1 (but NOT kotlin.reflect.KFunction1)
 */
fun isNumberedFunctionClassFqName(fqName: FqNameUnsafe): Boolean {
    return fqName.startsWith(KotlinBuiltIns.BUILT_INS_PACKAGE_NAME) &&
           fqName.getFunctionalClassKind() == FunctionClassDescriptor.Kind.Function
}

fun DeclarationDescriptor.getFunctionalClassKind(): FunctionClassDescriptor.Kind? {
    if (this !is ClassDescriptor) return null
    if (!KotlinBuiltIns.isUnderKotlinPackage(this)) return null

    return fqNameUnsafe.getFunctionalClassKind()
}

private fun FqNameUnsafe.getFunctionalClassKind(): FunctionClassDescriptor.Kind? {
    if (!isSafe || isRoot) return null

    return BuiltInFictitiousFunctionClassFactory.getFunctionalClassKind(shortName().asString(), toSafe().parent())
}


fun KotlinType.getReceiverTypeFromFunctionType(): KotlinType? {
    assert(isBuiltinFunctionalType) { "Not a function type: $this" }
    return if (isTypeAnnotatedWithExtensionFunctionType) arguments.first().type else null
}

fun KotlinType.getReturnTypeFromFunctionType(): KotlinType {
    assert(isBuiltinFunctionalType) { "Not a function type: $this" }
    return arguments.last().type
}

fun KotlinType.replaceReturnType(newReturnType: KotlinType): KotlinType {
    assert(isBuiltinFunctionalType) { "Not a function type: $this"}
    val argumentsWithNewReturnType = arguments.toMutableList().apply { set(size - 1, TypeProjectionImpl(newReturnType)) }
    return replace(newArguments = argumentsWithNewReturnType)
}

fun KotlinType.getValueParameterTypesFromFunctionType(): List<TypeProjection> {
    assert(isBuiltinFunctionalType) { "Not a function type: $this" }
    val arguments = arguments
    val first = if (isBuiltinExtensionFunctionalType) 1 else 0
    val last = arguments.size - 1
    assert(first <= last) { "Not an exact function type: $this" }
    return arguments.subList(first, last)
}

fun KotlinType.getValueParameterTypesFromCallableReflectionType(isCallableTypeWithExtension: Boolean): List<TypeProjection> {
    assert(ReflectionTypes.isKCallableType(this)) { "Not a callable reflection type: $this" }
    val arguments = arguments
    val first = if (isCallableTypeWithExtension) 1 else 0
    val last = arguments.size - 1
    assert(first <= last) { "Not an exact function type: $this" }
    return arguments.subList(first, last)
}

fun KotlinType.extractFunctionalTypeFromSupertypes(): KotlinType {
    assert(isBuiltinFunctionalTypeOrSubtype) { "Not a function type or subtype: $this" }
    return if (isBuiltinFunctionalType) this else supertypes().first { it.isBuiltinFunctionalType }
}

fun KotlinType.getPureArgumentsForFunctionalTypeOrSubtype(): List<KotlinType> {
    assert(isBuiltinFunctionalTypeOrSubtype) { "Not a function type or subtype: $this" }
    return extractFunctionalTypeFromSupertypes().arguments.dropLast(1).map { it.type }
}

fun KotlinType.extractParameterNameFromFunctionTypeArgument(): Name? {
    val annotation = annotations.findAnnotation(KotlinBuiltIns.FQ_NAMES.parameterName) ?: return null
    val name = (annotation.allValueArguments.values.singleOrNull() as? StringValue)
                       ?.value
                       ?.takeIf { Name.isValidIdentifier(it) }
               ?: return null
    return Name.identifier(name)
}

fun getFunctionTypeArgumentProjections(
        receiverType: KotlinType?,
        parameterTypes: List<KotlinType>,
        parameterNames: List<Name>?,
        returnType: KotlinType,
        builtIns: KotlinBuiltIns
): List<TypeProjection> {
    val arguments = ArrayList<TypeProjection>(parameterTypes.size + (if (receiverType != null) 1 else 0) + 1)

    arguments.addIfNotNull(receiverType?.asTypeProjection())

    parameterTypes.mapIndexedTo(arguments) { index, type ->
        val name = parameterNames?.get(index)?.takeUnless { it.isSpecial }
        val typeToUse = if (name != null) {
            val parameterNameAnnotation = BuiltInAnnotationDescriptor(
                    builtIns,
                    KotlinBuiltIns.FQ_NAMES.parameterName,
                    mapOf(Name.identifier("name") to StringValue(name.asString()))
            )
            type.replaceAnnotations(Annotations.create(type.annotations + parameterNameAnnotation))
        }
        else {
            type
        }
        typeToUse.asTypeProjection()
    }

    arguments.add(returnType.asTypeProjection())

    return arguments
}

@JvmOverloads
fun createFunctionType(
    builtIns: KotlinBuiltIns,
    annotations: Annotations,
    receiverType: KotlinType?,
    parameterTypes: List<KotlinType>,
    parameterNames: List<Name>?,
    returnType: KotlinType,
    suspendFunction: Boolean = false
): SimpleType {
    val arguments = getFunctionTypeArgumentProjections(receiverType, parameterTypes, parameterNames, returnType, builtIns)
    val parameterCount = if (receiverType == null) parameterTypes.size else parameterTypes.size + 1
    val classDescriptor = getFunctionDescriptor(builtIns, parameterCount, suspendFunction)

    // TODO: preserve laziness of given annotations
    val typeAnnotations = if (receiverType != null) annotations.withExtensionFunctionAnnotation(builtIns) else annotations

    return KotlinTypeFactory.simpleNotNullType(typeAnnotations, classDescriptor, arguments)
}

fun Annotations.hasExtensionFunctionAnnotation() = hasAnnotation(KotlinBuiltIns.FQ_NAMES.extensionFunctionType)

fun Annotations.withoutExtensionFunctionAnnotation() =
    FilteredAnnotations(this, true) { it != KotlinBuiltIns.FQ_NAMES.extensionFunctionType }

fun Annotations.withExtensionFunctionAnnotation(builtIns: KotlinBuiltIns) =
    if (hasAnnotation(KotlinBuiltIns.FQ_NAMES.extensionFunctionType)) {
        this
    } else {
        Annotations.create(this + BuiltInAnnotationDescriptor(builtIns, KotlinBuiltIns.FQ_NAMES.extensionFunctionType, emptyMap()))
    }

fun getFunctionDescriptor(builtIns: KotlinBuiltIns, parameterCount: Int, isSuspendFunction: Boolean) =
    if (isSuspendFunction) builtIns.getSuspendFunction(parameterCount) else builtIns.getFunction(parameterCount)

fun getKFunctionDescriptor(builtIns: KotlinBuiltIns, parameterCount: Int, isSuspendFunction: Boolean) =
    if (isSuspendFunction) builtIns.getKSuspendFunction(parameterCount) else builtIns.getKFunction(parameterCount)
