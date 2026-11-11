package com.medassist.fhir.web;

import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Enumerations;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /fhir/metadata} 的能力声明。
 *
 * <p>为什么必须真实：CapabilityStatement 是客户端（含 Connectathon 测试工具）
 * 决定"能不能用、怎么用"的唯一入口。声明了却没实现，比不声明更糟——
 * 对端会按声明发请求，然后把 404 当成我们"接口不稳定"。
 * 因此这里只列**已实现**的 read / search-type 与检索参数。
 */
@Component
public class FhirCapabilities {

    private record Param(String name, String type) {
    }

    public CapabilityStatement build(String baseUrl) {
        CapabilityStatement cs = new CapabilityStatement();
        cs.setName("MedAssistFHIRR4");
        cs.setTitle("MedAssist 医院业务平台 FHIR R4 开放层");
        cs.setStatus(Enumerations.PublicationStatus.ACTIVE);
        cs.setExperimental(false);
        cs.setDateElement(new DateTimeType(Instant.now().toString()));
        cs.setPublisher("MedAssist");
        cs.setDescription("""
                只读 FHIR R4 资源服务：患者主索引、就诊/住院、结构化危急值（Observation）、
                处方明细（MedicationRequest）。写入仍走各业务域的命令接口（审批、库存、收费规则不可绕过）。
                """);
        cs.setKind(CapabilityStatement.CapabilityStatementKind.INSTANCE);
        cs.getSoftware().setName("MedAssist Platform").setVersion("0.1.0");
        cs.getImplementation().setDescription("MedAssist FHIR 开放层").setUrl(baseUrl);
        cs.setFhirVersion(Enumerations.FHIRVersion._4_0_1);
        cs.setFormat(List.of(new CodeType("json"), new CodeType("xml")));

        CapabilityStatement.CapabilityStatementRestComponent rest = cs.addRest();
        rest.setMode(CapabilityStatement.RestfulCapabilityMode.SERVER);
        rest.setDocumentation("所有资源均要求 fhir:read 权限；数据按院区收敛，PHI 明文另需 fhir:read_phi。");

        addResource(rest, "Patient", """
                        患者主索引。identifier 支持院内患者号（urn:medassist:patient-no）
                        与跨系统标识（其他 system 或 urn:medassist:crosswalk:<系统>）。
                        合并后的档案以 active=false + link(replaced-by) 返回。
                        """,
                List.of(new Param("identifier", "token"), new Param("name", "string"),
                        new Param("active", "token"), new Param("_id", "token")));

        addResource(rest, "Encounter", """
                        就诊与住院登记。门诊/急诊/住院统一为 Encounter：
                        class = AMB / EMER / IMP，status 由就诊与住院状态机联合推导。
                        """,
                List.of(new Param("patient", "reference"), new Param("status", "token"),
                        new Param("date", "date"), new Param("_id", "token")));

        addResource(rest, "Observation", """
                        结构化检验指标（当前来源：已留痕的危急值指标，含参考范围与危急程度）。
                        category 仅支持 laboratory。
                        """,
                List.of(new Param("patient", "reference"), new Param("category", "token"),
                        new Param("status", "token"), new Param("date", "date"),
                        new Param("_id", "token")));

        addResource(rest, "MedicationRequest", """
                        处方明细（一条明细一个资源）。status 由处方状态机映射：
                        PENDING_REVIEW/APPROVED=active，DISPENSED=completed，REJECTED/CANCELLED=cancelled。
                        """,
                List.of(new Param("patient", "reference"), new Param("status", "token"),
                        new Param("date", "date"), new Param("_id", "token")));

        return cs;
    }

    private void addResource(CapabilityStatement.CapabilityStatementRestComponent rest,
                             String type, String documentation, List<Param> params) {
        CapabilityStatement.CapabilityStatementRestResourceComponent resource =
                rest.addResource().setType(type).setDocumentation(documentation);
        resource.addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.READ);
        resource.addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE);
        for (Param param : params) {
            resource.addSearchParam().setName(param.name()).setType(searchParamType(param.type()));
        }
    }

    private static Enumerations.SearchParamType searchParamType(String code) {
        return switch (code) {
            case "token" -> Enumerations.SearchParamType.TOKEN;
            case "string" -> Enumerations.SearchParamType.STRING;
            case "reference" -> Enumerations.SearchParamType.REFERENCE;
            case "date" -> Enumerations.SearchParamType.DATE;
            case "number" -> Enumerations.SearchParamType.NUMBER;
            case "uri" -> Enumerations.SearchParamType.URI;
            default -> Enumerations.SearchParamType.SPECIAL;
        };
    }
}
