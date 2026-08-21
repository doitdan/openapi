package io.github.doitdan.openapi.sample

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/samples")
class SampleController {
    @GetMapping
    fun getSamples(
        @RequestParam status: SampleStatus,
    ) = SampleResponse(status, "code")

    @PostMapping
    fun createSample(
        @RequestBody request: SampleRequest,
    ) = SampleResponse(SampleStatus.ACTIVE, request.code)

    @PostMapping("/bulk")
    fun createSample(
        @RequestBody requests: List<SampleRequest>,
    ) = requests.map { SampleResponse(SampleStatus.ACTIVE, it.code) }

    @GetMapping("/{sampleId}")
    fun getSample(
        @PathVariable sampleId: Long,
    ) = SampleResponse(SampleStatus.ACTIVE, sampleId.toString())

    @GetMapping(path = ["/alias", "/alias-two"])
    fun getAliases() = listOf(SampleResponse(SampleStatus.ACTIVE, "alias"))
}

data class SampleRequest(
    var status: SampleStatus = SampleStatus.ACTIVE,
    @field:SampleEnumRef(SampleStatus::class)
    var code: String = "",
    var memo: String? = null,
    var attempts: Int? = null,
)

data class SampleResponse(
    val status: SampleStatus,
    val code: String,
    val isSynced: Boolean = true,
    val hasChild: Boolean = false,
)
