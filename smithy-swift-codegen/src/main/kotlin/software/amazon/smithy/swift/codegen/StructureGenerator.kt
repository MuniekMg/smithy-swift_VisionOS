/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.amazon.smithy.swift.codegen

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.codegen.core.SymbolProvider
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.model.traits.RetryableTrait
import software.amazon.smithy.swift.codegen.customtraits.NestedTrait
import software.amazon.smithy.swift.codegen.customtraits.SwiftBoxTrait
import software.amazon.smithy.swift.codegen.integration.ProtocolGenerator
import software.amazon.smithy.swift.codegen.integration.SectionId
import software.amazon.smithy.swift.codegen.model.expectShape
import software.amazon.smithy.swift.codegen.model.getTrait
import software.amazon.smithy.swift.codegen.model.hasTrait
import software.amazon.smithy.swift.codegen.model.isError
import software.amazon.smithy.swift.codegen.model.nestedNamespaceType
import software.amazon.smithy.swift.codegen.model.recursiveSymbol
import software.amazon.smithy.swift.codegen.model.toLowerCamelCase
import software.amazon.smithy.swift.codegen.utils.errorShapeName
import software.amazon.smithy.swift.codegen.utils.toUpperCamelCase

class StructureGenerator(
    private val model: Model,
    private val symbolProvider: SymbolProvider,
    private val writer: SwiftWriter,
    private val shape: StructureShape,
    private val settings: SwiftSettings,
    private val serviceErrorProtocolSymbol: Symbol? = null
) {

    private val membersSortedByName: List<MemberShape> = shape.allMembers.values.sortedBy { it.toLowerCamelCase() }
    private var memberShapeDataContainer: MutableMap<MemberShape, Pair<String, Symbol>> = mutableMapOf()

    init {
        for (member in membersSortedByName) {
            val memberName = symbolProvider.toMemberName(member)
            val memberSymbol = symbolProvider.toSymbol(member)
            memberShapeDataContainer[member] = Pair(memberName, memberSymbol)
        }
    }

    private val structSymbol: Symbol by lazy {
        symbolProvider.toSymbol(shape)
    }

    fun render() {
        writer.putContext("struct.name", structSymbol.name.toUpperCamelCase())
        if (shape.isError) {
            renderErrorStructure()
        } else {
            renderNonErrorStructure()
        }
        writer.removeContext("struct.name")
    }

    /**
     * Generates an appropriate Swift type for a Smithy Structure shape without error trait.
     * If the structure is a recursive nested type it will generate a boxed member Box<T>.
     *
     * For example, given the following Smithy model:
     *
     * ```
     * namespace smithy.example
     *
     * structure Person {
     *     @required
     *     name: String,
     *     age: Integer,
     * }
     *
     * ```
     * We will generate the following:
     * ```
     * public struct Person {
     *     public let name: String
     *     public let age: Int?
     *     public init(
     *         name: String,
     *         age: int? = nil
     *     )
     *     {
     *         self.name = name
     *         self.age = age
     *     }
     * }
     * ```
     */
    private fun renderNonErrorStructure() {
        val isNestedType = shape.hasTrait<NestedTrait>()
        if (isNestedType) {
            val service = model.expectShape<ServiceShape>(settings.service)
            writer.openBlock("extension ${service.nestedNamespaceType(symbolProvider)} {", "}") {
                generateStruct()
            }
        } else {
            generateStruct()
        }
    }

    private fun generateStruct() {
        writer.writeShapeDocs(shape)
        writer.writeAvailableAttribute(model, shape)
        writer.openBlock("public struct \$struct.name:L: \$N {", SwiftTypes.Protocols.Equatable)
            .call { generateStructMembers() }
            .write("")
            .call { generateInitializerForStructure(false) }
            .closeBlock("}")
            .write("")
    }

    private fun generateStructMembers() {
        membersSortedByName.forEach {
            var (memberName, memberSymbol) = memberShapeDataContainer.getOrElse(it) { return@forEach }
            writer.writeMemberDocs(model, it)
            if (it.hasTrait<SwiftBoxTrait>()) {
                writer.addImport(SwiftDependency.CLIENT_RUNTIME.target)
                memberSymbol = memberSymbol.recursiveSymbol()
            }

            writer.writeAvailableAttribute(model, it)
            writer.write("public var \$L: \$T", memberName, memberSymbol)
        }
    }

    private fun generateInitializerForStructure(error: Boolean) {
        val hasMembers = membersSortedByName.isNotEmpty()

        if (hasMembers) {
            writer.openBlock("public init(", ")") {
                for ((index, member) in membersSortedByName.withIndex()) {
                    val (memberName, memberSymbol) = memberShapeDataContainer.getOrElse(member) { Pair(null, null) }
                    if (memberName == null || memberSymbol == null) continue
                    val terminator = if (index == membersSortedByName.size - 1) "" else ","
                    val symbolToUse = if (member.hasTrait(SwiftBoxTrait::class.java)) memberSymbol.recursiveSymbol() else memberSymbol
                    writer.write("\$L: \$D$terminator", memberName, symbolToUse)
                }
            }
            writer.openBlock("{", "}") {
                val path = "properties.".takeIf { error } ?: ""
                membersSortedByName.forEach {
                    val (memberName, _) = memberShapeDataContainer.getOrElse(it) { return@forEach }
                    writer.write("self.$path\$L = \$L", memberName, memberName)
                }
            }
        } else {
            writer.write("public init() { }")
        }
    }

    /**
     * Generates an appropriate Swift type for a Smithy Structure shape with error trait.
     *
     * For example, given the following Smithy model:
     *
     * ```
     * namespace smithy.example
     *
     * @error("client")
     * @retryable
     * @httpError(429)
     * structure ThrottlingError {
     *     @required
     *     message: String
     * }
     *
     * ```
     * We will generate the following:
     * ```
     * public struct ThrottlingError: ServiceError {
     *     public var _headers: Headers?
     *     public var _message: String?
     *     public var _requestID: String?
     *     public var _retryable: Bool = true
     *     public var _statusCode: HttpStatusCode?
     *     public var _type: ErrorType = .client
     *     public var message: String?
     *
     *     public init(
     *         message: String
     *     )
     *     {
     *         self.message = message
     *     }
     * }
     * ```
     */
    private fun renderErrorStructure() {
        assert(shape.getTrait(ErrorTrait::class.java).isPresent)
        writer.writeShapeDocs(shape)
        writer.addImport(SwiftDependency.CLIENT_RUNTIME.target)

        serviceErrorProtocolSymbol?.let {
            writer.addImport(it)
            writer.putContext("error.protocol", it)
        } ?: run {
            writer.putContext("error.protocol", ProtocolGenerator.DefaultServiceErrorProtocolSymbol)
        }

        writer.writeAvailableAttribute(model, shape)
        writer.openBlock(
            "public struct \$struct.name:L: \$L, \$error.protocol:L, \$L, \$L {",
            ClientRuntimeTypes.Core.ModeledError,
            ClientRuntimeTypes.Http.HttpError,
            SwiftTypes.Error
        )
            .call { generateErrorStructMembers() }
            .write("")
            .call { generateInitializerForStructure(true) }
            .closeBlock("}")
            .write("")

        writer.removeContext("error.protocol")
    }

    object AdditionalErrorMembers : SectionId

    private fun generateErrorStructMembers() {
        if (membersSortedByName.isNotEmpty()) {
            writer.write("")
            writer.openBlock("public struct Properties {", "}") {
                membersSortedByName.forEach {
                    val (memberName, memberSymbol) = memberShapeDataContainer.getOrElse(it) { return@forEach }
                    writer.writeMemberDocs(model, it)
                    writer.writeAvailableAttribute(model, it)
                    writer.write("public internal(set) var \$L: \$D", memberName, memberSymbol)
                }
            }
            writer.write("")
            writer.write("public internal(set) var properties = Properties()")
        }
        val errorTypeString = shape.errorShapeName(symbolProvider)
        writer.write("public static var typeName: \$N { \$S }", SwiftTypes.String, errorTypeString)
        val errorTrait = shape.getTrait<ErrorTrait>()
        if (errorTrait != null && errorTrait.isClientError) {
            writer.write("public static var fault: ErrorFault { .client }")
        } else if (errorTrait != null && errorTrait.isServerError) {
            writer.write("public static var fault: ErrorFault { .server }")
        }
        val retryableTrait = shape.getTrait<RetryableTrait>()
        val isRetryable = retryableTrait != null
        val isThrottling = retryableTrait?.throttling ?: false
        writer.write("public static var isRetryable: \$N { \$L }", SwiftTypes.Bool, isRetryable)
        writer.write("public static var isThrottling: \$N { \$L }", SwiftTypes.Bool, isThrottling)
        writer.write("public internal(set) var httpResponse = HttpResponse()")
        writer.write("public internal(set) var message: \$T", SwiftTypes.String)
        writer.write("public internal(set) var requestID: \$T", SwiftTypes.String)
        writer.declareSection(AdditionalErrorMembers)
    }
}
