package com.dazito.oauthexample.service.impl;

import com.dazito.oauthexample.dao.FileRepository;
import com.dazito.oauthexample.dao.StorageRepository;
import com.dazito.oauthexample.model.*;
import com.dazito.oauthexample.model.type.SomeType;
import com.dazito.oauthexample.model.type.UserRole;
import com.dazito.oauthexample.service.FileService;
import com.dazito.oauthexample.service.UserService;
import com.dazito.oauthexample.service.dto.request.FileUpdateDto;
import com.dazito.oauthexample.service.dto.response.*;
import liquibase.util.file.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class FileServiceImpl implements FileService {

    @Value("${root.path}")
    Path root;
    @Value("${path.downloadFile}")
    String downloadPath;
    @Value("${content.admin}")
    String contentName;
    @Resource(name = "userService")
    UserService userServices;

    private final StorageRepository storageRepository;
    private final FileRepository fileRepository;

    @Autowired
    public FileServiceImpl(StorageRepository storageRepository, FileRepository fileRepository) {
        this.storageRepository = storageRepository;
        this.fileRepository = fileRepository;
    }

    // upload multipart file on the server
    @Override
    public FileUploadedDto upload(MultipartFile file, Long parentId) throws IOException {
        if (file == null) return null;

        String originalFilename = file.getOriginalFilename();

        AccountEntity currentUser = userServices.getCurrentUser();
        Organization organization = currentUser.getOrganization();
        UserRole role = currentUser.getRole();
        String rootReference = currentUser.getContent().getRoot();
        Path rootPath;

        rootPath = this.root;
        if (role == UserRole.USER) rootPath = Paths.get(rootReference);
        if (!Files.exists(rootPath)) return null;

        String extension = FilenameUtils.getExtension(originalFilename);
        String name = FilenameUtils.getBaseName(originalFilename);

        String uuidString = generateStringUuid();

        String pathNewFile = rootPath + File.separator + uuidString;
        file.transferTo(new File(pathNewFile));

        Long size = file.getSize();

        FileEntity fileEntity = new FileEntity();
        fileEntity.setName(name);
        fileEntity.setUuid(uuidString);
        fileEntity.setOwner(currentUser);
        fileEntity.setSize(size);
        fileEntity.setExtension(extension);
        fileEntity.setOrganization(currentUser.getOrganization());

        StorageElement foundStorageElement = findStorageElementDependingOnTheParent(parentId, organization);

        fileEntity.setParentId(foundStorageElement);

        storageRepository.saveAndFlush(fileEntity);

        return responseFileUploaded(fileEntity);
    }

    @Override
    public void setSizeForParents(Long size, StorageDto storageDtoParent) {

        if (storageDtoParent == null) return;
        Long sizeParent = storageDtoParent.getSize();
        sizeParent = sizeParent + size;
        storageDtoParent.setSize(sizeParent);
        if (storageDtoParent.getType().equals(SomeType.CONTENT)) return;
        StorageDto preParent = storageDtoParent.getParent();
        if (storageDtoParent.getParent() != null) setSizeForParents(size, preParent);
    }

    @Override
    public FileUploadedDto editFile(FileUpdateDto fileUpdateDto) {

        AccountEntity currentUser = userServices.getCurrentUser();

        String name = fileUpdateDto.getNewName();
        Long parent = fileUpdateDto.getNewParentId();
        String uuid = fileUpdateDto.getUuid();

        FileEntity foundFile = findByUUIDInFileRepo(uuid);
        AccountEntity owner = foundFile.getOwner();

        boolean canChange = checkPermissionsOnStorageChanges(currentUser, owner, foundFile);
        if (!canChange) return null;

        StorageElement newParent = findByIdInStorageRepo(parent);
        foundFile.setName(name);
        foundFile.setParentId(newParent);

        fileRepository.saveAndFlush(foundFile);

        return responseFileUploaded(foundFile);
    }

    @Override
    public FileUploadedDto updateFile(MultipartFile file, String uuid) throws IOException {
        if (file == null)return null;

        AccountEntity currentUser = userServices.getCurrentUser();
        FileEntity foundFile = findByUUIDInFileRepo(uuid);
        if (foundFile == null) return null;
        Long parentId = foundFile.getParentId().getId();
        AccountEntity owner = foundFile.getOwner();
        Organization organization = currentUser.getOrganization();

        boolean canChange = checkPermissionsOnStorageChanges(currentUser, owner, foundFile);
        if (!canChange) return null;

        UserRole role = currentUser.getRole();
        String rootReference = currentUser.getContent().getRoot();
        Path rootPath;

        rootPath = this.root;
        if (role == UserRole.USER) rootPath = Paths.get(rootReference);
        if (!Files.exists(rootPath)) return null;

        String originalFilename = file.getOriginalFilename();
        String extension = FilenameUtils.getExtension(originalFilename);

        String name = foundFile.getName();

        String pathNewFile = rootPath + File.separator + uuid;
        file.transferTo(new File(pathNewFile));

        Long size = file.getSize();

        FileEntity fileEntity = new FileEntity();
        fileEntity.setName(name);
        fileEntity.setUuid(uuid);
        fileEntity.setOwner(currentUser);
        fileEntity.setSize(size);
        fileEntity.setExtension(extension);
        fileEntity.setOrganization(currentUser.getOrganization());

        storageRepository.delete(foundFile);

        StorageElement foundStorageElement = findStorageElementDependingOnTheParent(parentId, organization);

        fileEntity.setParentId(foundStorageElement);

        storageRepository.saveAndFlush(fileEntity);

        return responseFileUploaded(fileEntity);
    }

    public boolean checkPermissionsOnStorageChanges(AccountEntity currentUser, AccountEntity owner, StorageElement foundFile) {
        boolean checkedOnTheAdmin = userServices.adminRightsCheck(currentUser);
        if (!checkedOnTheAdmin) {
            boolean checkedMatchesOwner = matchesOwner(currentUser.getId(), owner.getId());
            if (!checkedMatchesOwner) return false;
        }
        Organization organizationUser = currentUser.getOrganization();
        Organization organizationFile = foundFile.getOrganization();

        boolean checkedMatchesOrganization = matchesOrganizations(organizationUser, organizationFile);
        if(!checkedMatchesOrganization) return false;
        return true;
    }

    public boolean matchesOrganizations(Organization organizationUser, Organization organizationFile) {
        String organizationNameUser = organizationUser.getOrganizationName();
        String organizationNameFile = organizationFile.getOrganizationName();
        return organizationNameUser.equals(organizationNameFile);
    }

    @Override
    public void deleteStorage(Long id) {
        AccountEntity currentUser = userServices.getCurrentUser();
        StorageElement foundStorage = findByIdInStorageRepo(id);
        AccountEntity owner = foundStorage.getOwner();
        SomeType type = foundStorage.getType();

        boolean canChange = checkPermissionsOnStorageChanges(currentUser,owner,foundStorage);
        if (!canChange) return;

        List<StorageElement>childChild = new ArrayList<>();

        if (!type.equals(SomeType.FILE)){
            List<StorageElement> listChildren = storageRepository.findByParentId(foundStorage);
            childChild.add(foundStorage);
            deleteChildFiles(childChild, listChildren);
            storageRepository.delete(childChild);
        }
    }

    private void deleteChildFiles(List<StorageElement>childChild, List<StorageElement> listChildren) {
        for (StorageElement element : listChildren) {
            childChild.add(element);
            List<StorageElement> listChildrenElement = storageRepository.findByParentId(element);
            List<StorageElement> byParentId = storageRepository.findByParentId(element);
            if (byParentId.size() != 0) deleteChildFiles(childChild, listChildren);
        }
    }

    // download file by uuid and response
    @Override
    public ResponseEntity<org.springframework.core.io.Resource> download(String uuid) throws IOException {

        AccountEntity currentUser = userServices.getCurrentUser();
        Long idCurrent = currentUser.getId();

        StorageElement fileEntityFromDB = findByUUIDInFileRepo(uuid);
        AccountEntity fileOwner = fileEntityFromDB.getOwner();
        Long ownerId = fileOwner.getId();

        if (!matchesOwner(idCurrent, ownerId)) {
            if (!userServices.adminRightsCheck(currentUser)) return null; // user is not admin and not owner of the file
        }

        Path filePath = setFilePathDependingOnTheUserRole(currentUser, uuid);

        if (!Files.exists(filePath)) return null;

        ByteArrayResource resource = new ByteArrayResource(Files.readAllBytes(filePath));

        return ResponseEntity.ok().body(resource);
    }

    // create root for all directories and files(for Admins) or for one User
    @Override
    public Content createContent(AccountEntity newUser) {

        UserRole role = newUser.getRole();
        String nameNewFolder = newUser.getEmail();
        Organization organization = newUser.getOrganization();

        Content content = new Content();

        switch (role) {
            case USER:
                content.setName("Content " + newUser.getEmail());
                createSinglePath(root + File.separator + nameNewFolder);
                content.setRoot(root + File.separator + nameNewFolder);
                break;
            case ADMIN:
                content.setName("CONTENT_" + organization.getOrganizationName());
                content.setRoot(root.toString());
                break;
        }
        content.setParentId(null);
        content.setSize(0L);
        content.setOrganization(organization);

        return content;
    }

    @Override
    public StorageElement findStorageElementDependingOnTheParent(Long parentId, Organization organization) {
        StorageElement foundStorageElement;
        if (parentId != 0) {
            foundStorageElement = findByIdInStorageRepo(parentId);
        } else {
            foundStorageElement = findByNameInStorageRepo("CONTENT_" + organization.getOrganizationName());
        }
        return foundStorageElement;
    }

    @Override
    public String generateStringUuid() {
        UUID uuid = UUID.randomUUID();
        return uuid + "";
    }

    @Override
    public FileUploadedDto responseFileUploaded(FileEntity fileEntity) {
        String name = fileEntity.getName();
        String extension = fileEntity.getExtension();
        Long size = fileEntity.getSize();
        String uuid = fileEntity.getUuid();

        FileUploadedDto fileUploadedDto = new FileUploadedDto();
        fileUploadedDto.setName(name + "." + extension);
        fileUploadedDto.setSize(size);
        fileUploadedDto.setReferenceToDownloadFile(downloadPath + uuid);

        return fileUploadedDto;
    }

    @Override
    public Path setFilePathDependingOnTheUserRole(AccountEntity currentUser, String uuid) {
        UserRole role = currentUser.getRole();
        Path filePath;
        if (role.equals(UserRole.USER)) {
            filePath = Paths.get(currentUser.getContent().getRoot(), uuid);
        } else {
            filePath = Paths.get(root.toString(), uuid);
        }
        return filePath;
    }

    @Override
    public File createSinglePath(String path) {
        File rootPath = new File(path);
        if (!rootPath.exists()) {
            if (rootPath.mkdir()) {
                System.out.println("Directory is created!");
            } else {
                System.out.println("Failed to create directory!");
            }
        }
        return rootPath;
    }

    @Override
    public StorageDto createHierarchy(Long id) {
        return buildStorageDto(id, null);
    }

    @Override
    public StorageDto buildStorageDto(Long id, StorageDto storageDtoParent) {
        StorageElement storageElement = findByIdInStorageRepo(id);

        Long idElement = storageElement.getId();
        String nameElement = storageElement.getName();
        SomeType typeElement = storageElement.getType();
        Long size = storageElement.getSize();

        StorageDto storageDto;

        if (typeElement.equals(SomeType.FILE)) {
            storageDto = new FileStorageDto();
        } else {
            storageDto = new DirectoryStorageDto();
        }

        storageDto.setId(idElement);
        storageDto.setName(nameElement);
        storageDto.setType(typeElement);
        storageDto.setParent(storageDtoParent);

        if (typeElement.equals(SomeType.FILE)) {
            setSizeForParents(size, storageDto);
            return storageDto;
        }

        storageDto.setSize(size);

        List<StorageElement> elementChildren = getChildListElement(storageElement);

        List<StorageDto> listChildrenDirectories = new ArrayList<>();
        List<StorageDto> listChildrenFiles = new ArrayList<>();

        for (StorageElement element : elementChildren) {
            SomeType type = element.getType();
            long elementId = element.getId();
            if (type.equals(SomeType.DIRECTORY)) listChildrenDirectories.add(buildStorageDto(elementId, storageDto));
            if (type.equals(SomeType.FILE)) listChildrenFiles.add(buildStorageDto(elementId, storageDto));
        }

        DirectoryStorageDto directoryStorageDtoDirectory = (DirectoryStorageDto) storageDto;
        directoryStorageDtoDirectory.setChildrenDirectories(listChildrenDirectories);
        directoryStorageDtoDirectory.setChildrenFiles(listChildrenFiles);

        return storageDto;
    }

    @Override
    public StorageElement findByIdInStorageRepo(Long id) {
        Optional<StorageElement> storageOptional = storageRepository.findById(id);
        return getStorageIfOptionalNotNull(storageOptional);
    }

    @Override
    public StorageElement findByNameInStorageRepo(String name) {
        Optional<StorageElement> storageOptional = storageRepository.findByName(name);
        return getStorageIfOptionalNotNull(storageOptional);
    }

    @Override
    public FileEntity findByUUIDInFileRepo(String uuid) {
        Optional<FileEntity> storageOptional = fileRepository.findByUuid(uuid);
        return getFileIfOptionalNotNull(storageOptional);
    }

    @Override
    public StorageElement getStorageIfOptionalNotNull(Optional<StorageElement> storageOptional) {
        boolean checkOnNull = userServices.checkOptionalOnNull(storageOptional);
        if (!checkOnNull) return null;
        return storageOptional.get();
    }

    @Override
    public FileEntity getFileIfOptionalNotNull(Optional<FileEntity> fileOptional) {
        boolean checkOnNull = userServices.checkOptionalOnNull(fileOptional);
        if (!checkOnNull) return null;
        return fileOptional.get();
    }

    @Override
    public List<StorageElement> getChildListElement(StorageElement storageElement) {
        return storageRepository.findByParentId(storageElement);
    }

    @Override
    public boolean matchesOwner(Long idCurrent, Long ownerId) {
        return Objects.equals(idCurrent, ownerId);
    }

    @Override
    public File createMultiplyPath(String path) {
        File rootPath2 = new File(path + "\\Directory\\Sub\\Sub-Sub");
        if (!rootPath2.exists()) {
            if (rootPath2.mkdirs()) {
                System.out.println("Multiple directories are created!");
            } else {
                System.out.println("Failed to create multiple directories!");
            }
        }
        return rootPath2;
    }
}
