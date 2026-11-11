package com.medassist.fhir.web;

import com.medassist.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 未实现的 FHIR 资源/路径统一返回 404 OperationOutcome。
 *
 * <p>只接 GET：写方法（POST/PUT/DELETE）走 Spring 的 405，明确告诉对端
 * "本服务只读"，而不是含糊的 404。
 */
@RestController
public class FhirFallbackController {

    private final FhirFormats formats;

    public FhirFallbackController(FhirFormats formats) {
        this.formats = formats;
    }

    @RequestMapping(value = {"/fhir", "/fhir/**", "/api/v2/fhir", "/api/v2/fhir/**"},
            method = RequestMethod.GET)
    public ResponseEntity<String> notFound(HttpServletRequest request) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(OperationOutcome.IssueType.NOTSUPPORTED)
                .setDiagnostics("不支持的 FHIR 资源或路径：" + request.getRequestURI()
                        + "。已实现的资源见 /fhir/metadata");
        FhirFormats.Format format;
        try {
            format = formats.negotiate(request.getParameter("_format"),
                    request.getHeader("Accept"));
        } catch (BusinessException e) {
            format = FhirFormats.Format.JSON;
        }
        return formats.response(outcome, format, HttpStatus.NOT_FOUND);
    }
}
