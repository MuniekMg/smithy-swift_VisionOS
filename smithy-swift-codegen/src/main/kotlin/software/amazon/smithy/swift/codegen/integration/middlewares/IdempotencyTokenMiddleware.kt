/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.amazon.smithy.swift.codegen.integration.middlewares

import software.amazon.smithy.codegen.core.SymbolProvider
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.traits.IdempotencyTokenTrait
import software.amazon.smithy.swift.codegen.ClientRuntimeTypes
import software.amazon.smithy.swift.codegen.SwiftWriter
import software.amazon.smithy.swift.codegen.integration.middlewares.handlers.MiddlewareShapeUtils
import software.amazon.smithy.swift.codegen.middleware.MiddlewarePosition
import software.amazon.smithy.swift.codegen.middleware.MiddlewareRenderable
import software.amazon.smithy.swift.codegen.middleware.MiddlewareStep
import software.amazon.smithy.swift.codegen.model.hasTrait

class IdempotencyTokenMiddleware(
    val model: Model,
    val symbolProvider: SymbolProvider
) : MiddlewareRenderable {
    override val name = "IdempotencyTokenMiddleware"
    override val middlewareStep = MiddlewareStep.INITIALIZESTEP
    override val position = MiddlewarePosition.AFTER
    override fun render(writer: SwiftWriter, op: OperationShape, operationStackName: String) {

        val inputShape = model.expectShape(op.input.get())
        val idempotentMember = inputShape.members().firstOrNull { it.hasTrait<IdempotencyTokenTrait>() }
        idempotentMember?.let {
            val idempotentMemberName = it.memberName.decapitalize()
            val outputShapeName = MiddlewareShapeUtils.outputSymbol(symbolProvider, model, op).name
            val outputErrorShapeName = MiddlewareShapeUtils.outputErrorSymbolName(op)
            writer.openBlock(
                "$operationStackName.${middlewareStep.stringValue()}.intercept(position: ${position.stringValue()}, id: \"${name}\") { (context, input, next) -> \$N<$outputShapeName> in", "}",
                ClientRuntimeTypes.Middleware.OperationOutput
            ) {
                writer.write("let idempotencyTokenGenerator = context.getIdempotencyTokenGenerator()")
                writer.write("var copiedInput = input")
                writer.openBlock("if input.$idempotentMemberName == nil {", "}") {
                    writer.write("copiedInput.$idempotentMemberName = idempotencyTokenGenerator.generateToken()")
                }
                writer.write("return try await next.handle(context: context, input: copiedInput)")
            }
        }
    }
}
