package com.liferay.multi.site.store.system;

import com.liferay.document.library.kernel.exception.NoSuchFileException;
import com.liferay.document.library.kernel.store.Store;
import com.liferay.document.library.kernel.util.DLUtil;
import com.liferay.petra.string.StringPool;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.Repository;
import com.liferay.portal.kernel.service.GroupLocalService;
import com.liferay.portal.kernel.service.RepositoryLocalServiceUtil;
import com.liferay.portal.kernel.util.ArrayUtil;
import com.liferay.portal.kernel.util.FileUtil;
import com.liferay.portal.kernel.util.Validator;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import java.io.*;
import java.util.*;

/**
 * Multi Site File Store
 *
 * Site specific storage:
 * /site-{groupId}/companyId/repositoryId/...
 *
 * @author Chanchal
 */
@Component(
        service = Store.class,
        property = "default=true",
        immediate = true
)
public class MultiSiteStore implements Store {
    private static Log _log = LogFactoryUtil.getLog(MultiSiteStore.class.getName());
    private static String ROOT_PATH = "/home/me/POC"; // it just a sample path, path should come from configuration

    @Reference
    private GroupLocalService _groupLocalService;

    // =========================================================
    // Core Add
    // =========================================================

    @Override
    public void addFile(
            long companyId, long repositoryId, String fileName,
            String versionLabel, InputStream inputStream) {

        if (_log.isInfoEnabled()) {
            _log.info(
                    "ADD FILE -> companyId=" + companyId +
                            ", repositoryId=" + repositoryId +
                            ", fileName=" + fileName +
                            ", version=" + versionLabel);
        }

        if (Validator.isNull(versionLabel)) {
            versionLabel = getHeadVersionLabel(
                    companyId, repositoryId, fileName);
        }

        try {
            File file = getFileNameVersionFile(
                    companyId, repositoryId, fileName, versionLabel);

            if (_log.isInfoEnabled()) {
                _log.info("Writing file to -> " + file.getAbsolutePath());
            }

            FileUtil.write(file, inputStream);
        }
        catch (IOException ioException) {
            _log.error("Error writing file", ioException);
            throw new SystemException(ioException);
        }
    }

    // =========================================================
    // Group Resolution
    // =========================================================

    private long _resolveGroupId(
            long companyId, long repositoryId, String fileName) {

        try {

            _log.info(
                    "Resolving groupId -> companyId=" + companyId +
                            ", repositoryId=" + repositoryId +
                            ", fileName=" + fileName);

            // -----------------------------
            // Normal Repository
            // -----------------------------
            if (repositoryId > 0) {

                Repository repository =
                        RepositoryLocalServiceUtil.fetchRepository(repositoryId);

                if (repository != null) {

                    long groupId = repository.getGroupId();

                    _log.info(
                            "Group resolved from Repository table -> " +
                                    groupId);

                    return groupId;
                }

                _log.warn(
                        "Repository not found, using repositoryId as groupId");

                return repositoryId;
            }

            // -----------------------------
            // Adaptive / Preview / Thumbnail
            // repositoryId == 0
            // -----------------------------
            if (repositoryId == 0 && Validator.isNotNull(fileName)) {

                long parsedGroupId = _extractGroupIdFromPath(fileName);

                if (parsedGroupId > 0) {

                    _log.info(
                            "Group resolved from file path -> " +
                                    parsedGroupId);

                    return parsedGroupId;
                }
            }

            // -----------------------------
            // Fallback Company Group
            // -----------------------------
            long companyGroupId =
                    _groupLocalService.getCompanyGroup(companyId)
                            .getGroupId();

            _log.warn(
                    "Fallback to Company Group -> " + companyGroupId);

            return companyGroupId;

        }
        catch (Exception e) {

            _log.error("Error resolving groupId", e);

            return 0;
        }
    }

    // =========================================================
    // Extract GroupId from Adaptive Path
    // =========================================================

    private long _extractGroupIdFromPath(String fileName) {

        try {

            String normalized = fileName.replace("\\", "/");

            String[] parts = normalized.split("/");

            for (String part : parts) {

                if (Validator.isNumber(part)) {

                    long value = Long.parseLong(part);

                    if (value > 10000) { // heuristic

                        _log.debug(
                                "Parsed groupId candidate from path -> " +
                                        value);

                        return value;
                    }
                }
            }
        }
        catch (Exception e) {

            _log.error("Error extracting groupId from path", e);
        }

        return 0;
    }

    // =========================================================
    // Base Directory Resolver
    // =========================================================

    private File _resolveBaseDir(
            long companyId, long repositoryId, String fileName) {

        long groupId = _resolveGroupId(
                companyId, repositoryId, fileName);

        String siteFolder = "site-" + groupId;

        File baseDir = new File(ROOT_PATH, siteFolder);

        if (!baseDir.exists()) {

            baseDir.mkdirs();

            _log.info("Created site directory -> " + baseDir);
        }

        _log.info(
                "STORE PATH -> companyId=" + companyId +
                        ", repositoryId=" + repositoryId +
                        ", groupId=" + groupId +
                        ", baseDir=" + baseDir.getAbsolutePath());

        return baseDir;
    }

    // =========================================================
    // Repository Dir
    // =========================================================

    public File getRepositoryDir(
            long companyId, long repositoryId, String fileName) {

        File rootDir = _resolveBaseDir(
                companyId, repositoryId, fileName);

        File repositoryDir = new File(
                rootDir, companyId + StringPool.SLASH + repositoryId);

        if (!repositoryDir.exists()) {
            repositoryDir.mkdirs();
        }

        return repositoryDir;
    }

    // =========================================================
    // File Helpers
    // =========================================================

    public File getFileNameDir(
            long companyId, long repositoryId, String fileName) {

        return new File(
                getRepositoryDir(companyId, repositoryId, fileName),
                fileName);
    }

    public File getFileNameVersionFile(
            long companyId, long repositoryId, String fileName,
            String version) {

        return new File(
                getFileNameDir(companyId, repositoryId, fileName),
                version);
    }

    public String getHeadVersionLabel(
            long companyId, long repositoryId, String fileName) {

        File fileNameDir =
                getFileNameDir(companyId, repositoryId, fileName);

        if (!fileNameDir.exists()) {
            return VERSION_DEFAULT;
        }

        String[] versionLabels = FileUtil.listFiles(fileNameDir);

        String headVersionLabel = VERSION_DEFAULT;

        for (String versionLabel : versionLabels) {
            if (DLUtil.compareVersions(versionLabel, headVersionLabel) > 0) {
                headVersionLabel = versionLabel;
            }
        }

        return headVersionLabel;
    }

    // =========================================================
    // Delete
    // =========================================================

    @Override
    public void deleteDirectory(
            long companyId, long repositoryId, String dirName) {

        File dirNameDir;

        if (Objects.equals(dirName, StringPool.SLASH)) {
            dirNameDir =
                    getRepositoryDir(companyId, repositoryId, dirName);
        }
        else {
            dirNameDir =
                    getDirNameDir(companyId, repositoryId, dirName);
        }

        if (!dirNameDir.exists()) {
            return;
        }

        File parentFile = dirNameDir.getParentFile();

        FileUtil.deltree(dirNameDir);

        _deleteEmptyAncestors(parentFile, companyId, repositoryId);
    }

    @Override
    public void deleteFile(
            long companyId, long repositoryId, String fileName,
            String versionLabel) {

        if (Validator.isNull(versionLabel)) {
            versionLabel =
                    getHeadVersionLabel(companyId, repositoryId, fileName);
        }

        File fileNameVersionFile =
                getFileNameVersionFile(
                        companyId, repositoryId, fileName, versionLabel);

        if (!fileNameVersionFile.exists()) {
            return;
        }

        File parentFile = fileNameVersionFile.getParentFile();

        fileNameVersionFile.delete();

        _deleteEmptyAncestors(parentFile, companyId, repositoryId);
    }

    private void _deleteEmptyAncestors(
            File file, long companyId, long repositoryId) {

        File rootDir =
                _resolveBaseDir(companyId, repositoryId, StringPool.BLANK);

        while ((file != null) && !file.equals(rootDir)) {
            if (!file.delete()) {
                return;
            }

            file = file.getParentFile();
        }
    }

    // =========================================================
    // Read
    // =========================================================

    @Override
    public InputStream getFileAsStream(
            long companyId, long repositoryId, String fileName,
            String versionLabel)
            throws NoSuchFileException {

        if (Validator.isNull(versionLabel)) {
            versionLabel =
                    getHeadVersionLabel(companyId, repositoryId, fileName);
        }

        File fileNameVersionFile =
                getFileNameVersionFile(
                        companyId, repositoryId, fileName, versionLabel);

        try {
            return new FileInputStream(fileNameVersionFile);
        }
        catch (FileNotFoundException fileNotFoundException) {

            _log.error(
                    "File not found -> " +
                            fileNameVersionFile.getAbsolutePath());

            throw new NoSuchFileException(
                    companyId, repositoryId, fileName, versionLabel,
                    fileNotFoundException);
        }
    }

    @Override
    public long getFileSize(
            long companyId, long repositoryId, String fileName,
            String versionLabel)
            throws NoSuchFileException {

        if (Validator.isNull(versionLabel)) {
            versionLabel =
                    getHeadVersionLabel(companyId, repositoryId, fileName);
        }

        File fileNameVersionFile =
                getFileNameVersionFile(
                        companyId, repositoryId, fileName, versionLabel);

        if (!fileNameVersionFile.exists()) {
            throw new NoSuchFileException(
                    companyId, repositoryId, fileName, versionLabel);
        }

        return fileNameVersionFile.length();
    }

    @Override
    public boolean hasFile(
            long companyId, long repositoryId, String fileName,
            String versionLabel) {

        if (Validator.isNull(versionLabel)) {
            versionLabel =
                    getHeadVersionLabel(companyId, repositoryId, fileName);
        }

        File fileNameVersionFile =
                getFileNameVersionFile(
                        companyId, repositoryId, fileName, versionLabel);

        return fileNameVersionFile.exists();
    }

    // =========================================================
    // Listing
    // =========================================================

    @Override
    public String[] getFileNames(
            long companyId, long repositoryId, String dirName) {

        File dirNameDir =
                getDirNameDir(companyId, repositoryId, dirName);

        if (!dirNameDir.exists()) {
            return new String[0];
        }

        List<String> fileNames = new ArrayList<>();

        _getFileNames(fileNames, dirName, dirNameDir.getPath());

        Collections.sort(fileNames);

        return fileNames.toArray(new String[0]);
    }

    public File getDirNameDir(
            long companyId, long repositoryId, String dirName) {

        return getFileNameDir(companyId, repositoryId, dirName);
    }

    protected void _getFileNames(
            List<String> fileNames, String dirName, String path) {

        String[] pathDirNames = FileUtil.listDirs(path);

        if (ArrayUtil.isNotEmpty(pathDirNames)) {
            for (String pathDirName : pathDirNames) {

                String subdirName;

                if (Validator.isBlank(dirName)) {
                    subdirName = pathDirName;
                }
                else {
                    subdirName =
                            dirName + StringPool.SLASH + pathDirName;
                }

                _getFileNames(
                        fileNames,
                        subdirName,
                        path + StringPool.SLASH + pathDirName);
            }
        }
        else if (!dirName.isEmpty()) {

            File file = new File(path);

            if (file.isDirectory()) {
                fileNames.add(dirName);
            }
        }
    }

    @Override
    public String[] getFileVersions(
            long companyId, long repositoryId, String fileName) {

        File fileNameDir =
                getFileNameDir(companyId, repositoryId, fileName);

        if (!fileNameDir.exists()) {
            return StringPool.EMPTY_ARRAY;
        }

        String[] versions = FileUtil.listFiles(fileNameDir);

        Arrays.sort(versions, DLUtil::compareVersions);

        return versions;
    }

}