package com.labosch.csillagtura.controller;

import com.labosch.csillagtura.config.auth.user.CustomOauth2User;
import com.labosch.csillagtura.entity.AccountJoinInitiation;
import com.labosch.csillagtura.entity.UserAcc;
import com.labosch.csillagtura.entity.UserEmailAddress;
import com.labosch.csillagtura.exceptions.DisplayAsUserAlertException;
import com.labosch.csillagtura.repo.AccountJoinInitiationRepository;
import com.labosch.csillagtura.repo.ExternalAccountDetailRepository;
import com.labosch.csillagtura.repo.UserEmailAddressRepository;
import com.labosch.csillagtura.repo.UserAccRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

@Controller
public class AccountJoinController {
    private static final Logger logger = LoggerFactory.getLogger(AccountJoinController.class);
    @Autowired
    UserEmailAddressRepository userEmailAddressRepository;
    @Autowired
    UserAccRepository userAccRepository;
    @Autowired
    ExternalAccountDetailRepository externalAccountDetailRepository;
    @Autowired
    AccountJoinInitiationRepository accountJoinInitiationRepository;
    @Autowired
    TransactionTemplate transactionTemplate;

    @GetMapping("/account/joinOther")
    String getJoinOther(Model model, @AuthenticationPrincipal CustomOauth2User authenticationPrincipal) {
        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus transactionStatus) {
                authenticationPrincipal.refreshUserEntityIfNull_FromDB(userAccRepository);

                addExisting_JoinInitiation_ToModel(model, authenticationPrincipal.getUserAccEntity());
                addWaitingForApproval_JoinInitiations_ToModel(model, authenticationPrincipal.getUserAccEntity());
            }
        });


        return "account/joinOther";
    }

    @PostMapping("/account/joinOther")
    String postJoinInitiate(Model model, @RequestParam("action") String action, @RequestParam(name = "subAction", required = false) String subAction, @RequestParam(name = "targetEmailAddress", required = false) String targetEmailAddress, @RequestParam(name = "initiationId", required = false) Long initiationId, @AuthenticationPrincipal CustomOauth2User authenticationPrincipal) {
        Boolean redirectToBaseJoinPage = transactionTemplate.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus transactionStatus) {
                return handleMainSwitch(model, action, subAction, targetEmailAddress, initiationId, authenticationPrincipal);
            }
        });

        if (redirectToBaseJoinPage) {
            logger.info("Redirecting to base account join page.");
            return "redirect:/account/joinOther";
        }

        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus transactionStatus) {
                authenticationPrincipal.refreshUserEntityIfNull_FromDB(userAccRepository);
                addExisting_JoinInitiation_ToModel(model, authenticationPrincipal.getUserAccEntity());
                addWaitingForApproval_JoinInitiations_ToModel(model, authenticationPrincipal.getUserAccEntity());
            }
        });
        return "account/joinOther";
    }

    public Boolean handleMainSwitch(Model model, String action, String subAction, String targetEmailAddress, Long initiationId, CustomOauth2User authenticationPrincipal) {
        authenticationPrincipal.refreshUserEntityIfNull_FromDB(userAccRepository);

        if (authenticationPrincipal.getUserAccEntity() == null)
            throw new RuntimeException("Your account seems missing.");

        switch (action) {
            case "initiateJoin":
                return handleInitiateJoin(model, targetEmailAddress, authenticationPrincipal.getUserAccEntity());
            case "cancelInitiatedJoin":
                return handleCancelInitiatedJoin(model, authenticationPrincipal.getUserAccEntity());
            case "judgeInitiation":
                return handleJudgeInitiation(model, subAction, initiationId, authenticationPrincipal.getUserAccEntity());
            default:
                logger.info("No action found for post request on account/joinOther.");
                return true;
        }

    }

    private boolean handleCancelInitiatedJoin(Model model, UserAcc currentUserAcc) {
        try {
            if (currentUserAcc.getInitiatedAccountJoinInitiation() == null)
                throw new DisplayAsUserAlertException("There aren't any join requests initiated by you!");

            accountJoinInitiationRepository.delete(currentUserAcc.getInitiatedAccountJoinInitiation());
            currentUserAcc.setInitiatedAccountJoinInitiation(null);

        } catch (DisplayAsUserAlertException e) {
            model.addAttribute("initiationStatus", "error");
            model.addAttribute("statusMessage", e.getMessage());
        }

        return false;
    }

    private boolean handleInitiateJoin(Model model, String targetEmailAddress, UserAcc currentUserAcc) {
        try {
            if (targetEmailAddress == null || targetEmailAddress.isBlank())
                throw new DisplayAsUserAlertException("Your given e-mail address is blank!");

            Optional<UserEmailAddress> targetedUserEmailAddress = userEmailAddressRepository.findByEmail(targetEmailAddress);
            if (targetedUserEmailAddress.isEmpty())
                throw new DisplayAsUserAlertException("E-mail address is not found!");

            UserAcc targetedUserAcc = targetedUserEmailAddress.get().getUserAcc();
            if (targetedUserAcc == null)
                throw new DisplayAsUserAlertException("The targeted account is not found!");

            if (!targetedUserAcc.getEnabled())
                throw new DisplayAsUserAlertException("The targeted account is currently disabled!");

            if (targetedUserAcc.getId().equals(currentUserAcc.getId()))
                throw new DisplayAsUserAlertException("The given e-mail address belongs to this account already.");

            AccountJoinInitiation alreadyExistingInitiationByCurrentAccount = currentUserAcc.getInitiatedAccountJoinInitiation();

            if (alreadyExistingInitiationByCurrentAccount != null)
                throw new DisplayAsUserAlertException("You can have only one pending initiation by user. Approve the current one from the other account or cancel it from this one!");

            AccountJoinInitiation accountJoinInitiation = new AccountJoinInitiation();
            accountJoinInitiation.setInitiatorUserAcc(currentUserAcc);
            accountJoinInitiation.setApproverUserAcc(targetedUserAcc);
            accountJoinInitiationRepository.save(accountJoinInitiation);

            currentUserAcc.setInitiatedAccountJoinInitiation(accountJoinInitiation);

            model.addAttribute("initiationStatus", "success");
        } catch (DisplayAsUserAlertException e) {
            model.addAttribute("initiationStatus", "error");
            model.addAttribute("statusMessage", e.getMessage());
        }

        return false;
    }

    private boolean handleJudgeInitiation(Model model, String subAction, Long initiationId, UserAcc currentUserAcc) {
        try {
            if (subAction == null || initiationId == null)
                return true;

            Optional<AccountJoinInitiation> accountJoinInitiationOptional = accountJoinInitiationRepository.findById(initiationId);
            if (accountJoinInitiationOptional.isEmpty())
                throw new DisplayAsUserAlertException("Join request is not found.");

            AccountJoinInitiation accountJoinInitiation = accountJoinInitiationOptional.get();
            if (!accountJoinInitiation.getApproverUserAcc().equalsById(currentUserAcc))
                throw new DisplayAsUserAlertException("You cannot decide on this join request. You are not the approver.");

            UserAcc initiatorUserAcc = accountJoinInitiation.getInitiatorUserAcc();
            UserAcc approverUserAcc = accountJoinInitiation.getApproverUserAcc();

            switch (subAction) {
                case "joinIntoApprover":
                    logger.info("Joining join request into approver.");
                    joinUserAccounts(initiatorUserAcc, approverUserAcc, accountJoinInitiation);
                    break;
                case "joinIntoInitiator":
                    logger.info("Joining join request into initiator.");
                    joinUserAccounts(approverUserAcc, initiatorUserAcc, accountJoinInitiation);
                    break;
                case "reject":
                    logger.info("Deleting (rejecting) join request.");

                    currentUserAcc.getAccountJoinInitiationsToApprove().forEach((init) -> {
                        if (init.getId().equals(initiationId))
                            currentUserAcc.getAccountJoinInitiationsToApprove().remove(init);
                    });
                    accountJoinInitiationRepository.delete(accountJoinInitiation);
                    break;
                default:
                    logger.info("No subAction found for judgeInitiation post request on account/joinOther.");
                    return true;
            }
        } catch (DisplayAsUserAlertException e) {
            model.addAttribute("initiationStatus", "error");
            model.addAttribute("statusMessage", e.getMessage());
        }

        return false;
    }

    void joinUserAccounts(UserAcc fromUserAcc, UserAcc toUserAcc, AccountJoinInitiation accountJoinInitiation) {
        if (fromUserAcc.equalsById(toUserAcc))
            throw new RuntimeException("Error joining User Accounts: fromUser and toUser are the same account!");
        logger.info("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        fromUserAcc.setJoinedInto(toUserAcc);
        fromUserAcc.setEnabled(false);

        // toUser.getUserEmailAddresses().addAll(fromUser.getUserEmailAddresses());

//        fromUser.getUserEmailAddresses().forEach(userEmailAddress -> {
//            userEmailAddress.setUser(toUser);
//            userEmailAddressRepository.save(userEmailAddress);
//        });

        userEmailAddressRepository.updateBelongingUser(fromUserAcc, toUserAcc);

        //  fromUser.getUserEmailAddresses().clear();

//        fromUser.getExternalAccountDetails().forEach(externalAccountDetail -> {
//            externalAccountDetail.setUser(toUser);
//            externalAccountDetailRepository.save(externalAccountDetail);
//        });

        externalAccountDetailRepository.updateBelongingUser(fromUserAcc, toUserAcc);


//        accountJoinInitiationRepository.deleteAll(fromUser.getAccountJoinInitiationsToApprove());
//        if (fromUser.getInitiatedAccountJoinInitiation() != null)
//            accountJoinInitiationRepository.delete(fromUser.getInitiatedAccountJoinInitiation());

        accountJoinInitiationRepository.deleteByInitiatorUserOrApproverUser(fromUserAcc);

        userAccRepository.save(fromUserAcc);
        userAccRepository.save(toUserAcc);

        accountJoinInitiationRepository.deleteById_DoNOTThrowExceptionIfNotExists(accountJoinInitiation.getId());
        logger.info("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
    }

    private void addWaitingForApproval_JoinInitiations_ToModel(Model model, UserAcc currentUserAcc) {
        currentUserAcc.getAccountJoinInitiationsToApprove().size();//Triggering lazy fetch
        model.addAttribute("waitingForApprovalInitiations", currentUserAcc.getAccountJoinInitiationsToApprove());
    }

    public void addExisting_JoinInitiation_ToModel(Model model, UserAcc currentUserAcc) {
        AccountJoinInitiation alreadyExistingInitiationByCurrentAccount = currentUserAcc.getInitiatedAccountJoinInitiation();


        if (alreadyExistingInitiationByCurrentAccount != null) {
            //Triggering lazy fetch
            if (alreadyExistingInitiationByCurrentAccount.getApproverUserAcc() != null
                    && alreadyExistingInitiationByCurrentAccount.getApproverUserAcc() != null) {
            }

            model.addAttribute("existingInitiation", alreadyExistingInitiationByCurrentAccount);
            model.addAttribute("submitEmailAddress", false);
        } else
            model.addAttribute("submitEmailAddress", true);
    }
}
