/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
import io.kotest.matchers.string.shouldContainOnlyOnce
import org.junit.jupiter.api.Test
import software.amazon.smithy.build.MockManifest
import software.amazon.smithy.swift.codegen.SwiftCodegenPlugin

class SensitiveTraitGeneratorTests {
    @Test
    fun `SensitiveTraitInRequestInput+CustomDebugStringConvertible`() {
        val manifest = setupTest()
        var extensionWithSensitiveTrait = manifest
            .getFileString("example/models/SensitiveTraitInRequestInput+CustomDebugStringConvertible.swift").get()
        extensionWithSensitiveTrait.shouldSyntacticSanityCheck()
        val expectedContents =
            """
            extension SensitiveTraitInRequestInput: Swift.CustomDebugStringConvertible {
                public var debugDescription: Swift.String {
                    "SensitiveTraitInRequestInput(foo: \(Swift.String(describing: foo)), baz: \"CONTENT_REDACTED\")"}
            }
            """.trimIndent()
        extensionWithSensitiveTrait.shouldContainOnlyOnce(expectedContents)
    }

    @Test
    fun `SensitiveTraitInRequestOutput+CustomDebugStringConvertible`() {
        val manifest = setupTest()
        var extensionWithSensitiveTrait = manifest
            .getFileString("example/models/SensitiveTraitInRequestOutputResponse+CustomDebugStringConvertible.swift").get()
        extensionWithSensitiveTrait.shouldSyntacticSanityCheck()
        val expectedContents =
            """
            extension SensitiveTraitInRequestOutputResponse: Swift.CustomDebugStringConvertible {
                public var debugDescription: Swift.String {
                    "CONTENT_REDACTED"
                }
            }
            """.trimIndent()
        extensionWithSensitiveTrait.shouldContainOnlyOnce(expectedContents)
    }

    @Test
    fun `AllSensitiveMemberStruct+CustomDebugStringConvertible`() {
        val manifest = setupTest()
        var extensionWithSensitiveTrait = manifest
            .getFileString("example/models/SensitiveTraitTestRequestOutputResponse+CustomDebugStringConvertible.swift").get()
        extensionWithSensitiveTrait.shouldSyntacticSanityCheck()
        val expectedContents =
            """
            extension SensitiveTraitTestRequestOutputResponse: Swift.CustomDebugStringConvertible {
                public var debugDescription: Swift.String {
                    "SensitiveTraitTestRequestOutputResponse(bar: \"CONTENT_REDACTED\", baz: \"CONTENT_REDACTED\", foo: \"CONTENT_REDACTED\")"}
            }
            """.trimIndent()
        extensionWithSensitiveTrait.shouldContainOnlyOnce(expectedContents)
    }

    private fun setupTest(): MockManifest {
        val model = javaClass.getResource("sensitive-trait-test.smithy").asSmithy()
        val manifest = MockManifest()
        val context = buildMockPluginContext(model, manifest, "smithy.example#Example")
        SwiftCodegenPlugin().execute(context)
        return manifest
    }
}
