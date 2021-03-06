package com.dazito.oauthexample.service.impl;

import com.dazito.oauthexample.dao.ChannelRepository;
import com.dazito.oauthexample.dao.StorageRepository;
import com.dazito.oauthexample.model.*;
import com.dazito.oauthexample.model.type.ResponseCode;
import com.dazito.oauthexample.model.type.SomeType;
import com.dazito.oauthexample.model.type.UserRole;
import com.dazito.oauthexample.service.*;
import com.dazito.oauthexample.service.dto.request.DirectoryDto;
import com.dazito.oauthexample.service.dto.request.StorageAddToChannelDto;
import com.dazito.oauthexample.service.dto.request.UpdateStorageOnChannel;
import com.dazito.oauthexample.service.dto.request.UserAddToChannelDto;
import com.dazito.oauthexample.service.dto.response.*;
import com.dazito.oauthexample.utils.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class ChannelServiceImpl implements ChannelService {

    @Autowired
    UserService userService;
    @Autowired
    StorageRepository storageRepository;
    @Autowired
    DirectoryService directoryService;
    @Autowired
    ChannelRepository channelRepository;
    @Autowired
    ContentService contentService;
    @Autowired
    StorageService storageService;
    @Autowired
    UtilService utilService;
    @Autowired
    FileService fileService;

    @Value("${root.path}")
    Path root;

    @Override
    @Transactional
    public ChannelCreatedDto createChannel(String name) throws AppException {
        AccountEntity currentUser = userService.getCurrentUser();
        userService.adminRightsCheck(currentUser);
        Channel channel = new Channel();
        channel.setName(name);
        channel.setOwner(currentUser);
        ArrayList<AccountEntity> listAccount = new ArrayList<>();
        channel.setListOwners(listAccount);
        ArrayList<StorageElement> listFiles = new ArrayList<>();
        channel.setParents(listFiles);
        channel.setOrganization(currentUser.getOrganization());

        channelRepository.saveAndFlush(channel);

        ChannelCreatedDto channelCreatedDto = new ChannelCreatedDto();
        channelCreatedDto.setChannelName(name);

        Channel foundChannel = channelRepository.findByName(name);
        channelCreatedDto.setId(foundChannel.getId());
        return channelCreatedDto;
    }

    @Override
    @Transactional
    public UserAddedToChannelDto addUserToChannel(UserAddToChannelDto userAddToChannelDto) throws AppException {
        AccountEntity currentUser = userService.getCurrentUser();
        userService.adminRightsCheck(currentUser);
        Long idUser = userAddToChannelDto.getIdUser();
        AccountEntity foundUser = userService.findByIdAccountRepo(idUser);
        String organizationNameFoundUser = foundUser.getOrganization().getOrganizationName();
        userService.isMatchesOrganization(organizationNameFoundUser, currentUser);

        Long idChannel = userAddToChannelDto.getIdChannel();
        Channel foundChannel = (Channel)findById(idChannel);
        List<AccountEntity> userListFromChannel = foundChannel.getListOwners();
        userListFromChannel.add(foundUser);
        foundChannel.setListOwners(userListFromChannel);

        channelRepository.saveAndFlush(foundChannel);

        UserAddedToChannelDto userAddedToChannelDto = new UserAddedToChannelDto();
        userAddedToChannelDto.setIdChannel(idChannel);
        userAddedToChannelDto.setIdUser(idUser);
        return userAddedToChannelDto;
    }

    @Override
    @Transactional
    public StorageAddedToChannelDto addStorageToChannel(StorageAddToChannelDto storageAddToChannelDto) throws AppException {
        AccountEntity currentUser = userService.getCurrentUser();
        userService.adminRightsCheck(currentUser);
        Long idStorage = storageAddToChannelDto.getIdStorage();
        StorageElement foundStorageElement = storageService.findById(idStorage);
        String organizationNameFoundStorage = foundStorageElement.getOrganization().getOrganizationName();
        userService.isMatchesOrganization(organizationNameFoundStorage, currentUser);

        Long idChannel = storageAddToChannelDto.getIdChannel();
        Channel foundChannel = (Channel)findById(idChannel);

        List<StorageElement> parentsStorageElement = foundStorageElement.getParents();
        parentsStorageElement.add(foundChannel);
        foundStorageElement.setParents(parentsStorageElement);

//        List<StorageElement> children = foundChannel.getChildren();
//        if (children == null) children = new ArrayList<>();
//        children.add(foundStorageElement);
//        foundChannel.setChildren(children);

        storageRepository.saveAndFlush(foundStorageElement);
//        channelRepository.saveAndFlush(foundChannel);

        StorageAddedToChannelDto storageAddedToChannelDto = new StorageAddedToChannelDto();
        storageAddedToChannelDto.setIdChannel(idChannel);
        storageAddedToChannelDto.setIdStorage(idStorage);
        return storageAddedToChannelDto;
    }

    @Override
    @Transactional
    public List<Long> getAllStorageElementsChannel(Long idChannel) throws AppException {
        AccountEntity currentUser = userService.getCurrentUser();
        Channel foundChannel = (Channel)findById(idChannel);

        boolean isHaveAccess = checkRightsCheck(currentUser, foundChannel);
        if (!isHaveAccess)
            throw new AppException("You do not have access to this channel", ResponseCode.DO_NOT_HAVE_ACCESS);

        List<StorageElement> storageElementList = foundChannel.getChildren();
        List<Long> storageElementListIds = new ArrayList<>();

        for (StorageElement element : storageElementList) {
            storageElementListIds.add(element.getId());
        }
        return storageElementListIds;
    }

    @Override
    @Transactional
    public Resource download(Long idChannel, Long id) throws AppException, IOException {
        AccountEntity currentUser = userService.getCurrentUser();
        Channel foundChannel = (Channel) findById(idChannel);

        boolean isHaveAccess = checkRightsCheck(currentUser, foundChannel);
        if (!isHaveAccess)
            throw new AppException("You do not have access to this channel", ResponseCode.DO_NOT_HAVE_ACCESS);

        FileEntity foundFile = fileService.findById(id);
        AccountEntity ownerFile = foundFile.getOwner();
        UserRole roleOwnerFile = ownerFile.getRole();

        String uuid = foundFile.getUuid();
        Path filePath = null;

        switch (roleOwnerFile) {
            case USER:
                filePath = Paths.get(contentService.findContentByUser(ownerFile).getRoot(), uuid);
                break;
            case ADMIN:
                filePath = Paths.get(root.toString(), uuid);
                break;
        }
        boolean onChannel = checkStorageOnChannel(foundChannel, foundFile);
        if (!onChannel)
            throw new AppException("Storage element is not exist on channel.", ResponseCode.NO_SUCH_ELEMENT);

        if (!Files.exists(filePath))
            throw new AppException("The path does not exist or has an error.", ResponseCode.PATH_NOT_EXIST);

        ByteArrayResource resource = new ByteArrayResource(Files.readAllBytes(filePath));
        return resource;
    }

    @Override
    @Transactional
    public DeletedStorageDto deleteStorageFromChannel(Long idChannel, Long idStorage) throws AppException {
        AccountEntity currentUser = userService.getCurrentUser();
        StorageElement foundStorageElement = storageService.findById(idStorage);
        String organizationNameFoundStorage = foundStorageElement.getOrganization().getOrganizationName();
        userService.isMatchesOrganization(organizationNameFoundStorage, currentUser);

        Channel foundChannel = (Channel)findById(idChannel);
        boolean onChannel = checkStorageOnChannel(foundChannel, foundStorageElement);
        if (!onChannel)
            throw new AppException("Storage element is not exist on channel.", ResponseCode.NO_SUCH_ELEMENT);

        if(!(foundStorageElement instanceof StorageElementWithChildren)){
            List<StorageElement> parents = foundStorageElement.getParents();
            for (StorageElement parent : parents) {
                boolean isPartChannel = checkStorageOnChannel(foundChannel, parent);
                if (!isPartChannel) continue;
                StorageElementWithChildren castedParent = (StorageElementWithChildren) parent;
                List<StorageElement> children = castedParent.getChildren();
                children.remove(foundStorageElement);
                channelRepository.saveAndFlush(foundChannel);
            }
            return responseStorageDeleteFromChannel();
        }
        List<StorageElement> listToDelete = new ArrayList<>();

        recursionForCreateListToDelete(listToDelete, foundChannel, foundStorageElement);
        deleteStorageFromParents(listToDelete, foundChannel);
        removeTopmostItem(foundStorageElement);

        return responseStorageDeleteFromChannel();
    }

    @Override
    @Transactional
    public DirectoryCreatedDto updateStorage(UpdateStorageOnChannel updateStorageOnChannel) throws AppException {
        Long idChannel = updateStorageOnChannel.getIdChannel();
        Long idEditStorage = updateStorageOnChannel.getIdEditStorage();
        Long idNewParent = updateStorageOnChannel.getIdNewParent();
        Long idCurrentParent = updateStorageOnChannel.getIdCurrentParent();
        if (idCurrentParent == null) idCurrentParent = idChannel;

        AccountEntity currentUser = userService.getCurrentUser();
        Channel foundChannel = (Channel)findById(idChannel);
        checkRightsCheck(currentUser, foundChannel);
        StorageElement foundStorage = storageService.findById(idEditStorage);
        checkStorageOnChannel(foundChannel, foundStorage);

        StorageElementWithChildren currentParent = (StorageElementWithChildren) findById(idCurrentParent);
        StorageElementWithChildren newParent = (StorageElementWithChildren) storageService.findById(idNewParent);
        List<StorageElement> childrenCurrentParent = currentParent.getChildren();
        childrenCurrentParent.remove(foundStorage);
        List<StorageElement> childrenNewParent = newParent.getChildren();
        childrenNewParent.add(foundStorage);

        channelRepository.saveAndFlush(foundChannel);

        DirectoryCreatedDto directoryCreatedDto = new DirectoryCreatedDto();
        directoryCreatedDto.setName(foundStorage.getName());
        directoryCreatedDto.setParentId(idNewParent);
        return directoryCreatedDto;
    }

    @Override
    public DeletedStorageDto deleteChannel(Long idChannel) throws AppException {
        AccountEntity currentUser = userService.getCurrentUser();
        Channel channel = (Channel)findById(idChannel);
        String name = channel.getName();
        String organizationNameChannel = channel.getOrganization().getOrganizationName();
        userService.isMatchesOrganization(organizationNameChannel, currentUser);
        channelRepository.delete(idChannel);

        DeletedStorageDto deletedStorageDto = new DeletedStorageDto();
        deletedStorageDto.setNameDeletedStorage(name);
        return deletedStorageDto;
    }

    @Override
    @Transactional
    public DirectoryCreatedDto createDirectory(DirectoryDto directoryDto) throws AppException {
        AccountEntity currentUser = userService.getCurrentUser();
        Long newParentId = directoryDto.getNewParentId();
        StorageElement foundParent = findById(newParentId);
        String organizationNameFoundStorage = foundParent.getOrganization().getOrganizationName();
        userService.isMatchesOrganization(organizationNameFoundStorage, currentUser);

        //TO ADD CHECK RIGHT

        Directory directory = new Directory();
        directory.setName(directoryDto.getNewName());
        directory.setOrganization(currentUser.getOrganization());
        directory.setOwner(currentUser);

        storageRepository.saveAndFlush(directory);

        Channel channelByStorage = findChannelByStorage(foundParent);

        StorageElementWithChildren castFoundParent = (StorageElementWithChildren) foundParent;

        List<StorageElement> children = castFoundParent.getChildren();
        if (children == null) children = new ArrayList<>();
        children.add(directory);

        ((StorageElementWithChildren) foundParent).setChildren(children);
        channelRepository.saveAndFlush(channelByStorage);

        DirectoryCreatedDto directoryCreatedDto = new DirectoryCreatedDto();
        directoryCreatedDto.setName(directoryDto.getNewName());
        directoryCreatedDto.setParentId(foundParent.getId());

        StorageElement foundDir = storageRepository.findByName(directoryDto.getNewName()).get();
        Long id = foundDir.getId();
        directoryCreatedDto.setId(id);
        return directoryCreatedDto;
    }

    private void removeTopmostItem(StorageElement foundStorageElement) {
        List<StorageElement> parents = foundStorageElement.getParents();
        for (StorageElement element : parents) {
            StorageElementWithChildren castedElement = (StorageElementWithChildren) element;
            List<StorageElement> children = castedElement.getChildren();
            children.remove(foundStorageElement);
        }
    }

    private void deleteStorageFromParents(List<StorageElement> listToDelete, Channel foundChannel) {
        for (StorageElement element : listToDelete) {
            List<StorageElement> parents = element.getParents();
            raiseEachParent(parents, element, foundChannel);
        }
    }

    private void raiseEachParent(List<StorageElement> parents, StorageElement foundStorageElement, Channel foundChannel) {
        for (StorageElement parent : parents) {
            // если объект ведет к Channel, который мы указали, тогда выполняем код дальше(он удаляется)
            // иначе пропускаем итерацию
            boolean partChannel = isPartChannel(parent, foundChannel);
            if (!partChannel) continue;
            StorageElementWithChildren castParent = (StorageElementWithChildren) parent;
            List<StorageElement> children = castParent.getChildren();
            children.remove(foundStorageElement);
        }
    }

    private void recursionForCreateListToDelete(List<StorageElement> listToDelete, StorageElement foundChannel, StorageElement foundStorageElement) {
        List<StorageElement> children = null;
        if (foundStorageElement instanceof StorageElementWithChildren) {
            StorageElementWithChildren castStorage = (StorageElementWithChildren)foundStorageElement;
            children = castStorage.getChildren();
            if (children.isEmpty()) return;
        }
        for (StorageElement child : children) {
            boolean partChannel = isPartChannel(child, foundChannel);
            if (partChannel) {
                listToDelete.add(child);
                if (child instanceof StorageElementWithChildren) {
                    recursionForCreateListToDelete(listToDelete, foundChannel, child);
                }
            }
        }
    }

    private boolean isPartChannel(StorageElement child, StorageElement foundChannel) {
        List<StorageElement> parents = child.getParents();
        for (StorageElement parent : parents) {
            if (parent.getId().equals(foundChannel.getId())){
                return true;
            }
            if (parent.getType() == SomeType.DIRECTORY) return isPartChannel(parent, foundChannel);
        }
        return false;
    }

    private DeletedStorageDto responseStorageDeleteFromChannel() {
        DeletedStorageDto deletedStorageDto = new DeletedStorageDto();
        deletedStorageDto.setNameDeletedStorage("Deleted");
        return deletedStorageDto;
    }

    private Channel findChannelByStorage(StorageElement foundStorage) throws AppException {
        SomeType type = foundStorage.getType();
        switch (type) {
            case CONTENT:
                throw new AppException("The type cannot be a Content.", ResponseCode.TYPE_MISMATCH);
            case FILE:
                throw new AppException("The type cannot be a File.", ResponseCode.TYPE_MISMATCH);
            case CHANNEL:
                return (Channel) foundStorage;
            case DIRECTORY:
                return recursForFindChannelParent(foundStorage);
        }
        throw new AppException("Channel is not found.", ResponseCode.NO_SUCH_ELEMENT);
    }

    private Channel recursForFindChannelParent(StorageElement foundStorage) throws AppException {
        List<StorageElement> parents = foundStorage.getParents();
        for (StorageElement element : parents) {
            SomeType type = element.getType();
            if (type == SomeType.CHANNEL) return (Channel) element;
            if (type == SomeType.FILE) continue;
            recursForFindChannelParent(element);
        }
        return null;
    }

    private boolean checkStorageOnChannel(Channel foundChannel, StorageElement foundFile) throws AppException {
        List<StorageElement> parents = foundFile.getParents();
        for (StorageElement element : parents) {
            if (element.getType() == SomeType.CHANNEL && element.getId() == foundChannel.getId()) return true;
            if (recursForFindChannelParent(element, foundChannel)) return true;
        }
        if (parents.isEmpty()) {
            if (foundFile.getType() == SomeType.CHANNEL && foundFile.getId() == foundChannel.getId()) return true;
        }
        return false;
    }

    private boolean recursForFindChannelParent(StorageElement transferElement, Channel foundChannel) throws AppException {
        List<StorageElement> parents = transferElement.getParents();
        for (StorageElement element : parents) {
            if (element.getType() == SomeType.CHANNEL && element.getId() == foundChannel.getId()) {
                return true;
            } else {
                recursForFindChannelParent(element, foundChannel);
            }
        }
        return false;
    }

    private boolean checkRightsCheck(AccountEntity currentUser, Channel foundChannel) throws AppException {
        UserRole role = currentUser.getRole();
        switch (role) {
            case USER:
                List<Channel> channelListUser = currentUser.getChannelList();
                Long idFoundChannel = foundChannel.getId();
                for (Channel channel : channelListUser) {
                    if (channel.getId().equals(idFoundChannel)) return true;
                }
                return false;
            case ADMIN:
                String organizationNameCurrentUser = currentUser.getOrganization().getOrganizationName();
                String organizationNameOwnerChannel = foundChannel.getOwner().getOrganization().getOrganizationName();
                utilService.isMatchesOrganization(organizationNameCurrentUser, organizationNameOwnerChannel);
                return true;
        }
        return true;
    }

    public StorageElement findById(Long id) throws AppException {
        Optional<StorageElement> foundOptional = storageRepository.findById(id);
        if (!foundOptional.isPresent())
            throw new AppException("No objects were found by your request.", ResponseCode.NO_SUCH_ELEMENT);
        return foundOptional.get();
    }

}
