package com.medassist.tech.pacs;

import com.medassist.tech.config.TechProperties;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.service.PacsDimseService;
import com.medassist.tech.web.PacsDimseDtos.FindStudiesReq;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Commands;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.Dimse;
import org.dcm4che3.net.PDVInputStream;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.BasicCFindSCP;
import org.dcm4che3.net.service.BasicCMoveSCP;
import org.dcm4che3.net.service.BasicCStoreSCP;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIMSE 协议级联调（本机内嵌 SCP，不依赖 Docker/PACS）。
 *
 * <p>验证的是"客户端真的能在网络上完成关联、C-ECHO、C-FIND、C-STORE、C-MOVE"，
 * 而不是只验证查询键长什么样。内嵌 SCP 的返回是固定的，因此断言可重复、无外部依赖。
 *
 * <p>C-MOVE 的 SCP 只返回成功与子操作计数（不真的回推影像）：实际回推由对端 PACS
 * 完成，本平台只负责发起请求与解析结果。真实影像回推需在院内 PACS 联调时验证。
 */
class Dcm4cheDimseProtocolTest {

    private static final String CALLED_AE = "TEST_SCP";
    private static final String STUDY_UID = "1.2.826.0.1.3680043.10.999";
    private static final String SERIES_UID = "1.2.826.0.1.3680043.10.999.1";
    private static final String SOP_UID = "1.2.826.0.1.3680043.10.999.1.1";

    private ScheduledExecutorService executor;
    private Device scp;
    private Dcm4cheDimseClient client;
    private final Attributes stored = new Attributes();

    @BeforeEach
    void startScp() throws Exception {
        executor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "dimse-scp-test");
            thread.setDaemon(true);
            return thread;
        });

        scp = new Device("TEST_SCP");
        scp.setScheduledExecutor(executor);
        scp.setExecutor(executor);
        Connection connection = new Connection();
        connection.setHostname("127.0.0.1");
        connection.setPort(freePort());
        ApplicationEntity ae = new ApplicationEntity(CALLED_AE);
        ae.setAssociationAcceptor(true);
        ae.setAssociationInitiator(false);
        ae.addConnection(connection);
        ae.addTransferCapability(new TransferCapability(null, UID.Verification,
                TransferCapability.Role.SCP, UID.ImplicitVRLittleEndian));
        ae.addTransferCapability(new TransferCapability(null,
                UID.StudyRootQueryRetrieveInformationModelFind,
                TransferCapability.Role.SCP, UID.ImplicitVRLittleEndian));
        ae.addTransferCapability(new TransferCapability(null,
                UID.StudyRootQueryRetrieveInformationModelMove,
                TransferCapability.Role.SCP, UID.ImplicitVRLittleEndian));
        ae.addTransferCapability(new TransferCapability(null, UID.CTImageStorage,
                TransferCapability.Role.SCP, UID.ExplicitVRLittleEndian));

        DicomServiceRegistry registry = new DicomServiceRegistry();
        registry.addDicomService(new BasicCEchoSCP());
        registry.addDicomService(new FindScp());
        registry.addDicomService(new MoveScp());
        registry.addDicomService(new StoreScp());
        scp.setDimseRQHandler(registry);
        scp.addConnection(connection);
        scp.addApplicationEntity(ae);
        scp.bindConnections();

        int port = connection.getPort();
        TechProperties props = new TechProperties(null, null, new TechProperties.Pacs(
                null, null, null, null, new TechProperties.Dimse(true, "127.0.0.1", port,
                CALLED_AE, "MEDASSIST_HIS", Duration.ofSeconds(3), Duration.ofSeconds(10),
                1024L * 1024, List.of(CALLED_AE))));
        client = new Dcm4cheDimseClient(props);
    }

    @AfterEach
    void stopScp() {
        if (scp != null) {
            scp.unbindConnections();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("C-ECHO / C-FIND / C-STORE / C-MOVE 在本机 SCP 上全部走通")
    void dimseRoundTrip() throws Exception {
        PacsDimseGateway.EchoResult echo = client.echo();
        assertThat(echo.statusCode()).isZero();
        assertThat(echo.remoteAe()).isEqualTo(CALLED_AE);

        List<PacsDimseGateway.StudyResult> studies = client.findStudies(
                new PacsDimseGateway.StudyQuery("P999", null, "A999", null, null, "CT"));
        assertThat(studies).hasSize(1);
        assertThat(studies.get(0).studyInstanceUid()).isEqualTo(STUDY_UID);
        assertThat(studies.get(0).patientName()).isEqualTo("TEST DIMSE");

        byte[] dicom = dicomBytes();
        PacsDimseGateway.StoreResult store = client.store(dicom);
        assertThat(store.statusCode()).isZero();
        assertThat(store.sopInstanceUid()).isEqualTo(SOP_UID);
        assertThat(stored.getString(Tag.StudyInstanceUID)).isEqualTo(STUDY_UID);

        PacsDimseGateway.MoveResult move = client.moveStudy(STUDY_UID, CALLED_AE);
        assertThat(move.statusCode()).isZero();
        assertThat(move.completed()).isEqualTo(2);
    }

    @Test
    @DisplayName("服务层到协议层全链路：校验/脱敏/审计 + 真实 DIMSE 往返")
    void serviceRoundTrip() throws Exception {
        PacsDimseService service = new PacsDimseService(client,
                org.mockito.Mockito.mock(TechRepository.class),
                new ObjectMapper(), new SimpleMeterRegistry());

        assertThat(service.echo("tech01").statusCode()).isZero();
        assertThat(service.findStudies(
                new FindStudiesReq(null, null, "A999", null, null, "CT"), "tech01", false))
                .singleElement()
                .satisfies(view -> {
                    // 无 phi:read：短患者号整体掩码，姓名只留首字
                    assertThat(view.patientId()).isEqualTo("****");
                    assertThat(view.patientName()).startsWith("T").doesNotContain("DIMSE");
                });
        assertThat(service.store(new MockMultipartFile("file", "ct.dcm",
                "application/dicom", dicomBytes()), "tech01").statusCode()).isZero();
        assertThat(service.move(STUDY_UID, CALLED_AE, "tech01").completed()).isEqualTo(2);
    }

    /** dcm4che 的 Connection 不接受 0，这里先探测一个可绑定端口再交给 SCP。 */
    private static int freePort() throws IOException {
        for (int i = 0; i < 30; i++) {
            int candidate = 20000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(20000);
            try (ServerSocket socket = new ServerSocket(candidate)) {
                return socket.getLocalPort();
            } catch (IOException ignored) {
                // 端口被占用，换一个
            }
        }
        throw new IOException("找不到可用端口");
    }

    private static byte[] dicomBytes() throws IOException {
        Attributes dataset = new Attributes();
        dataset.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192");
        dataset.setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage);
        dataset.setString(Tag.SOPInstanceUID, VR.UI, SOP_UID);
        dataset.setString(Tag.StudyInstanceUID, VR.UI, STUDY_UID);
        dataset.setString(Tag.SeriesInstanceUID, VR.UI, SERIES_UID);
        dataset.setString(Tag.PatientID, VR.LO, "P999");
        dataset.setString(Tag.PatientName, VR.PN, "TEST^DIMSE");
        dataset.setString(Tag.AccessionNumber, VR.SH, "A999");
        dataset.setString(Tag.Modality, VR.CS, "CT");
        dataset.setString(Tag.StudyDate, VR.DA, "20261111");
        dataset.setString(Tag.StudyTime, VR.TM, "120000");
        dataset.setInt(Tag.SeriesNumber, VR.IS, 1);
        dataset.setInt(Tag.InstanceNumber, VR.IS, 1);
        dataset.setInt(Tag.Rows, VR.US, 2);
        dataset.setInt(Tag.Columns, VR.US, 2);
        dataset.setInt(Tag.SamplesPerPixel, VR.US, 1);
        dataset.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");
        dataset.setInt(Tag.BitsAllocated, VR.US, 16);
        dataset.setInt(Tag.BitsStored, VR.US, 16);
        dataset.setInt(Tag.HighBit, VR.US, 15);
        dataset.setInt(Tag.PixelRepresentation, VR.US, 0);
        dataset.setBytes(Tag.PixelData, VR.OW, new byte[8]);

        Attributes fmi = dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DicomOutputStream dos = new DicomOutputStream(out, UID.ExplicitVRLittleEndian)) {
            // writeDataset(fmi, ds) 已经包含文件元信息；再 writeFileMetaInformation 会写重复
            dos.writeDataset(fmi, dataset);
        }
        return out.toByteArray();
    }

    private final class FindScp extends BasicCFindSCP {
        private FindScp() {
            super(UID.StudyRootQueryRetrieveInformationModelFind);
        }

        @Override
        public void onDimseRQ(Association as, PresentationContext pc, Dimse dimse,
                              Attributes rq, Attributes keys) throws IOException {
            Attributes match = new Attributes();
            match.setString(Tag.StudyInstanceUID, VR.UI, STUDY_UID);
            match.setString(Tag.PatientID, VR.LO, "P999");
            match.setString(Tag.PatientName, VR.PN, "TEST^DIMSE");
            match.setString(Tag.AccessionNumber, VR.SH, "A999");
            match.setString(Tag.StudyDate, VR.DA, "20261111");
            match.setString(Tag.ModalitiesInStudy, VR.CS, "CT");
            match.setInt(Tag.NumberOfStudyRelatedSeries, VR.IS, 1);
            match.setInt(Tag.NumberOfStudyRelatedInstances, VR.IS, 1);
            as.writeDimseRSP(pc, Commands.mkCFindRSP(rq, Status.Pending), match);
            as.writeDimseRSP(pc, Commands.mkCFindRSP(rq, Status.Success), null);
        }
    }

    private final class MoveScp extends BasicCMoveSCP {
        private MoveScp() {
            super(UID.StudyRootQueryRetrieveInformationModelMove);
        }

        @Override
        public void onDimseRQ(Association as, PresentationContext pc, Dimse dimse,
                              Attributes rq, Attributes keys) throws IOException {
            Attributes rsp = Commands.mkCMoveRSP(rq, Status.Success);
            rsp.setInt(Tag.NumberOfCompletedSuboperations, VR.US, 2);
            as.writeDimseRSP(pc, rsp, null);
        }
    }

    private final class StoreScp extends BasicCStoreSCP {
        private StoreScp() {
            super(UID.CTImageStorage);
        }

        @Override
        protected void store(Association as, PresentationContext pc, Attributes rq,
                             PDVInputStream data, Attributes rsp) throws IOException {
            Attributes dataset = data.readDataset(pc.getTransferSyntax());
            stored.addAll(dataset);
            rsp.setInt(Tag.Status, VR.US, 0);
        }
    }
}
