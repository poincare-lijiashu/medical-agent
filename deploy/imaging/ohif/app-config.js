/*
 * OHIF v3 本地阅片配置（第 260 批）。
 *
 * 数据源指向本机 Orthanc 的 DICOMweb；换院区 PACS 时只需改 root。
 * 用 window.location.hostname 而不是写死 127.0.0.1：从其他机器访问控制台时，
 * 浏览器仍能解析到同一台宿主机（Orthanc 端口当前只绑回环，需按部署放开）。
 */
(function () {
  var host = window.location.hostname || '127.0.0.1';
  var root = 'http://' + host + ':8042/dicom-web';

  window.config = {
    routerBasename: '/',
    showStudyList: true,
    defaultDataSourceName: 'orthanc',
    dataSources: [
      {
        namespace: '@ohif/extension-default.dataSourcesModule.dicomweb',
        sourceName: 'orthanc',
        configuration: {
          friendlyName: 'MedAssist Orthanc',
          name: 'Orthanc',
          wadoUriRoot: root,
          qidoRoot: root,
          wadoRoot: root,
          qidoSupportsIncludeField: true,
          imageRendering: 'wadors',
          thumbnailRendering: 'wadors',
          enableStudyLazyLoad: true,
          supportsFriendlyHttpHeaders: false,
          omitQuotationForMultipartRequest: true
        }
      }
    ]
  };
})();
