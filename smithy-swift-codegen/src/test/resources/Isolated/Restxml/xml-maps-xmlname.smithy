$version: "1.0"

namespace aws.protocoltests.restxml

use aws.api#service
use aws.protocols#restXml
use smithy.test#httpRequestTests
use smithy.test#httpResponseTests

@service(sdkId: "Rest Xml maps")
@restXml
service RestXml {
    version: "2019-12-16",
    operations: [
        XmlMapsXmlName
    ]
}

@http(uri: "/XmlMapsXmlName", method: "POST")
operation XmlMapsXmlName {
    input: XmlMapsXmlNameInputOutput,
    output: XmlMapsXmlNameInputOutput
}

structure XmlMapsXmlNameInputOutput {
    myMap: XmlMapsXmlNameInputOutputMap,
}

map XmlMapsXmlNameInputOutputMap {
    @xmlName("Attribute")
    key: String,

    @xmlName("Setting")
    value: GreetingStruct
}

structure GreetingStruct {
    hi: String,
}
