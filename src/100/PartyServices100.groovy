import java.sql.Timestamp
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.entity.condition.EntityConditionBuilder
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.util.EntityUtil
import org.apache.ofbiz.service.ModelService
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.common.image.ImageTransform
import java.awt.image.*
import java.awt.Image
import java.util.Base64
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.imageio.ImageIO
import org.apache.commons.lang.RandomStringUtils


def boolean isAdmin(userLogin) {
    if(userLogin.userLoginId == 'system' || from("UserLoginSecurityGroup")
        .where(userLoginId: userLogin.userLoginId,
            groupId: "GROWERP_M_ADMIN").queryList())
    return true
    else false
}

def getCompanies() {
    Map result = success()
    List companyList
    String imageSize
    if (parameters.companyPartyId) {
        companyList = from('CompanyPreferenceAndClassification')
            .where([companyPartyId: parameters.companyPartyId])
            .queryList()
        imageSize = "GROWERP-MEDIUM"
    } else if (parameters.classificationId) {
        result.companies = []
        companyList = from('CompanyPreferenceAndClassification')
            .where([classificationId: parameters.classificationId])
            .queryList()
        imageSize = "GROWERP-SMALL"
    }

    companyList.each {
        email = runService('getPartyEmail',
            [   partyId: it.companyPartyId,
                contactMechPurposeTypeId: 'PRIMARY_EMAIL']).emailAddress
        users = runService('getUsers100', [:])?.users
        contents = from('PartyContent')
            .where([partyId: it.companyPartyId, partyContentTypeId: imageSize])
            .queryList()
        Map resultData
        if (contents)
            resultData = runService('getContentAndDataResource',
                [contentId: contents[0].contentId])
        company = [ // see model in https://github.com/growerp/growerp/blob/master/lib/models/company.dart
            partyId: it.companyPartyId,
            name: it.organizationName,
            classificationId: it.classificationId,
            classificationDescr: it.description,
            email: email,
            currencyId: it.baseCurrencyUomId,
            image: resultData? resultData.resultData.dataResource.imageDataResource.imageData : null,
            employees: users
        ]
        if (parameters.companyPartyId) result.company = company
        else if (parameters.classificationId) result.companies.add(company)
    }
    return result
}

def checkCompany() {
    Map result = success()
    parties = from(CompanyPreferenceAndClassification)
        .where(companyPartyId: parameters.companyPartyId)
        .queryList()
    if(partyList) result.ok = 'ok'
    return result
}

def updateCompany() { // can only update by admin own company
    Map result = success()
    if (isAdmin(userLogin) == false) return error("Not authorized!")
    companyPartyId = runService("getRelatedCompany100", [:]).companyPartyId
    oldCompany = runService("getCompanies100",
        [companyPartyId: companyPartyId]).company
    if (parameters.company.name != oldCompany.name) {
        runService('updatePartyGroup', [partyId: companyPartyId,
            groupName: parameters.company.name])}
    cm = from("PartyContactMechPurpose")
        .where(partyId: companyPartyId,
                contactMechPurposeTypeId: 'PRIMARY_EMAIL').queryList()
    if (parameters.company.email != oldCompany.email) {
        runService('createUpdatePartyEmailAddress',
            [ partyId: companyPartyId,
              contactMechId: cm[0].contactMechId,
              emailAddress: parameters.company.email,
              contactMechPurposeTypeId: 'PRIMARY_EMAIL'])}
    if (parameters.company.currencyId != oldCompany.currencyId) {
        accountingPref = select("partyId","currencyUomId")
            .from("PartyAccountingPreference")
            .where(partyId: companyPartyId).queryOne()
        accountingPref.currencyUomId = parameters.company.currencyId
        accountingPref.update() }
    result.company = runService("getCompanies100",
        [companyPartyId: companyPartyId]).company
    return result    
}

def registerUserAndCompany() {
    Map result = success()
    parameters.userLogin = from("UserLogin")
        .where([userLoginId: "system"]).queryOne();

    if (!parameters.companyPartyId) {
        companyPartyId = runService('createPartyGroup',
            [ groupName: parameters.companyName]).partyId
        runService('createPartyRole',
            [ partyId: companyPartyId,
              roleTypeId: 'INTERNAL_ORGANIZATIO'])
        runService('createPartyClassification',
            [ partyId: companyPartyId,
              partyClassificationGroupId: parameters.classificationId,
              fromDate: UtilDateTime.nowTimestamp()])
        runService('createPartyEmailAddress',
            [ partyId: companyPartyId,
              emailAddress: parameters.companyEmail,
              contactMechPurposeTypeId: 'PRIMARY_EMAIL'])
        runService('createPartyAcctgPreference',
            [ partyId: companyPartyId,
              baseCurrencyUomId: parameters.currencyId])
        productStoreId = runService('createProductStore',
            [ payToPartyId: companyPartyId,
              storeName: 'Store of ' + parameters.companyName]).productStoreId
        prodCatalogId = runService('createProdCatalog',
            [ catalogName: 'Catalog for company' + parameters.companyName,
              payToPartyId: companyPartyId]).prodCatalogId
        runService('createProductStoreCatalog',
            [ prodCatalogId: prodCatalogId,
              productStoreId: productStoreId,
              fromDate: UtilDateTime.nowTimestamp()])
    }
    user = [firstName: parameters.firstName,
            lastName: parameters.lastName,
            userGroupId: 'GROWERP_M_ADMIN',
            email: parameters.emailAddress,
            username: parameters.username]
    result.user = runService('createUser100',
        [ user: user, companyPartyId: companyPartyId])?.user
    resultCompany = runService("getCompanies100",
        [ partyId: companyPartyId]).company
    return result
}

def getUsers() {
    Map result = success()
    List userList
    if (parameters.userPartyId == userLogin?.partyId) { //users own data
        userList = from('PersonAndLoginGroup')
            .where([userPartyId: parameters.userPartyId])
            .queryList()
    } else if (isAdmin(parameters.userLogin)) { //get all users from own company
        companyPartyId = runService("getRelatedCompany100", [:]).companyPartyId
        userList = from('CompanyPersonAndLoginGroup') // by company
            .where([companyPartyId: companyPartyId])
            .queryList()            
    }
    String imageSize
    if (!parameters.userPartyId) {
        result.users = [];
        imageSize = "GROWERP-SMALL"
    } else {
        imageSize = "GROWERP-MEDIUM"
    }

    userList.each {
        contents = from('PartyContent')
            .where([partyId: it.userPartyId, partyContentTypeId: imageSize])
            .queryList()
        Map resultData
        if (contents)
            resultData = runService('getContentAndDataResource',
                [contentId: contents[0].contentId])
        resultEmail = runService('getPartyEmail',
            [ partyId: it.userPartyId,
              contactMechPurposeTypeId: 'PRIMARY_EMAIL'])
        // see model in https://github.com/growerp/growerp/blob/master/lib/models/user.dart
        user = [ 
            partyId: it.userPartyId,
            firstName: it.firstName,
            lastName: it.lastName,
            email: resultEmail.emailAddress,
            name: it.userLoginId,
            userGroupId: it.groupId,
            groupDescription: it.groupDescription,
            image: resultData?
                resultData.resultData.dataResource.imageDataResource.imageData : null,
        ]
        if (!parameters.userPartyId) result.users.add(user)
        else result.user = user
    }
    return result
}

def loadDefaultData() {
    
}
def createUser() {
    Map result = success()
    result.user = [:]
    // only admin can add employees in his own company
    loginUserCompanyId = runService("getRelatedCompany100", [:]).companyPartyId
    //String password = RandomStringUtils.randomAlphanumeric(6); 
    String password = 'qqqqqq9!'
    userPartyId = runService('createPerson',
        [ firstName: parameters.user.firstName,
          lastName: parameters.user.lastName]).partyId
    runService('createPartyEmailAddress',
        [ partyId: userPartyId,
          emailAddress: parameters.user.email,
          contactMechPurposeTypeId: 'PRIMARY_EMAIL'])
    loginResult = runService('createUserLogin',
        [ partyId: userPartyId,
          userLoginId: parameters.user.username,
          currentPassword: password,
          currentPasswordVerify: password])
    if (ServiceUtil.isError(loginResult)) return loginResult
    runService('addUserLoginToSecurityGroup',
        [ userLoginId: parameters.user.username,
          groupId: parameters.user.userGroupId,
          fromDate: UtilDateTime.nowTimestamp()])
    runService('addUserLoginToSecurityGroup', // do not use OFBIZ security system
        [ userLoginId: parameters.user.username,
          groupId: 'SUPER',
          fromDate: UtilDateTime.nowTimestamp()])
    if (parameters.user.userGroupId in ['GROWERP_M_ADMIN', 'GROWERP_M_EMPLOYEE']) {
        if (!parameters.companyPartyId) {
            parameters.companyPartyId = 
                runService('getRelatedCompany100',[:]).companyPartyId
        }
        runService("createPartyRelationship",
            [ partyIdFrom: parameters.companyPartyId,
              roleTypeIdFrom: "INTERNAL_ORGANIZATIO",
              partyIdTo: userPartyId,
              roleTypeIdTo: "_NA_",
              fromDate: UtilDateTime.nowTimestamp()])
    }
    if (parameters.base64) runService("createImages100",
        [ base64: parameters.base64,
          type: 'user',
          id: userPartyId])

    result.user = runService("getUsers100", [userPartyId: userPartyId])?.user
    return result
}
def updateUser() {
    Map result = success()

    if (parameters.user.partyId != userLogin.partyId) { // own data
        companyPartyId = runService("getRelatedCompany100", [:]).companyPartyId
        loginCompanyPartyId = runService("getRelatedCompany100", 
            [userpartyid: parameters.user.partyId]).companyPartyId
        if (companyPartyId != loginCompanyPartyId) { // own company
            if (!isAdmin(userLogin)) { // only admin can
                return error("No access to user ${parameters.user.partyId}")
            }
        }
    }

    oldUser = runService("getUsers100",[userPartyId: parameters.user.partyId]).user
    if (oldUser.lastName != parameters.user.lastName || 
            oldUser.firstName != parameters.user.firstName) {
        runService('updatePerson',
            [   partyId: parameters.user.partyId,
                firstName: parameters.user.firstName,
                lastName: parameters.user.lastName])
    }

    if (oldUser.email != parameters.user.email) { 
        cm = from("PartyContactMechPurpose")
            .where(partyId: parameters.user.partyId,
                    contactMechPurposeTypeId: 'PRIMARY_EMAIL').queryList()
        runService('createUpdatePartyEmailAddress',
            [   partyId: parameters.user.partyId,
                contactMechId: cm[0].contactMechId,
                emailAddress: parameters.user.email,
                contactMechPurposeTypeId: 'PRIMARY_EMAIL'])
    }
    if (oldUser.name != parameters.user.name) {
        loginResult = runService('updateUserLogin',
            [ userLoginId: parameters.user.username])
        if (ServiceUtil.isError(loginResult)) return loginResult
    }
    if (oldUser.userGroupId != parameters.user.userGroupid) {
        sec = from("UserLoginSecurityGroup")
                .where(userLoginId: parameters.user.name,
                        groupId: oldUser.userGroupId).queryList()
        runService('removeUserLoginToSecurityGroup',
            [ userLoginId: parameters.user.name,
            fromDate: sec[0].fromDate,
            groupId: oldUser.userGroupId])
        runService('addUserLoginToSecurityGroup',
            [ userLoginId: parameters.user.name,
            groupId: parameters.user.userGroupId,
            fromDate: UtilDateTime.nowTimestamp()])
    }
    if (parameters.user.image) runService("createImages100",
        [ base64: parameters.user.image,
          type: 'user',
          id: parameters.user.partyId])
    result.user = runService('getUsers100',
        [userPartyId: parameters.user.partyId])?.user
    return result
}
def deleteUser() {
    Map result = success()
    
}

def updatePassword() {
    Map result = success()
        
}

def resetPassword() {
    Map result = success()
        
}

def getAuthenticate() {
    Map result = success()
    result.user = runService("getUsers100", // get single user info
        [userPartyId: parameters.userLogin.partyId])?.user
    companyPartyId = runService("getRelatedCompany100", [:]).companyPartyId
    result.company = runService("getCompanies100", // get companyInfo
        [ companyPartyId: companyPartyId])?.company
    return result
}

def createImages() {
    Map result = success()
    byte[] imageBytes = Base64.getMimeDecoder().decode(parameters.base64);
    int fileSize = imageBytes.size()
    logInfo("======file size: ${fileSize}")
    drResult = runService("createDataResource", [:])
    drResult = runService("createImageDataResource",
        [imageData: imageBytes, dataResourceId: drResult.dataResourceId])
    contentResultLarge = runService("createContent",
        [dataResourceId: drResult.dataResourceId])

    // byte[] to buffered image
    BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
    logInfo("===end read")

    inst scaleFactor = 5000 / fileSize
    // resize image
    Image newImg = bufImg.getScaledInstance((int) (img.getWidth() * scaleFactor),
        (int) (img.getHeight() * scaleFactor), Image.SCALE_SMOOTH);
    BufferedImage bufNewImg = ImageTransform.toBufferedImage(newImg, bufImgType);

    // bufferedImage back to byte[]
    baos = new ByteArrayOutputStream();
    ImageIO.write( bufNewImg, "jpg", baos );
    baos.flush();
    imageBytes = baos.toByteArray();
    baos.close();
    
    drResult = runService("createDataResource", [:])
    drResult = runService("createImageDataResource",
        [imageData: imageBytes, dataResourceId: drResult.dataResourceId])
    contentResultMedium = runService("createContent",
        [dataResourceId: drResult.dataResourceId])

    int scaleFactor = 2000 / fileSize

    // resize image
    newImg = bufImg.getScaledInstance((int) (img.getWidth() * scaleFactor),
        (int) (img.getHeight() * scaleFactor), Image.SCALE_SMOOTH);
    bufNewImg = ImageTransform.toBufferedImage(newImg, bufImgType);
    // bufferedImage to byte[]
    baos = new ByteArrayOutputStream();
    ImageIO.write( bufNewImg, "jpg", baos );
    baos.flush();
    imageBytes = baos.toByteArray();
    baos.close();
    drResult = runService("createDataResource", [:])
    drResult = runService("createImageDataResource",
        [imageData: imageBytes, dataResourceId: drResult.dataResourceId])
    contentResultSmall = runService("createContent",
        [dataResourceId: drResult.dataResourceId])

    if (parameters.type in ['user', 'company']) {
        runService("createPartyContent",
            [ partyId: parameters.id,
              contentId: contentResultLarge.contentId,
              partyContentTypeId: 'GROWERP-LARGE'])
        runService("createPartyContent",
            [ partyId: parameters.id,
              contentId: contentResultMedium.contentId,
              partyContentTypeId: 'GROWERP-MEDIUM'])
        runService("createPartyContent",
            [ partyId: parameters.id,
              contentId: contentResultSmall.contentId,
              partyContentTypeId: 'GROWERP-SMALL'])
    }
    return result
}
