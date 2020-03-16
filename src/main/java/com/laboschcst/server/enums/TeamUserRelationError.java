package com.laboschcst.server.enums;

public enum TeamUserRelationError {
    INITIATOR_IS_DIFFERENT_THAN_ALTERED,
    INITIATOR_IS_NOT_LEADER_OF_TEAM_OF_ALTERED,
    INITIATOR_IS_SAME_AS_ALTERED,
    YOU_HAVE_TO_BE_A_LEADER_TO_DO_THIS_OPERATION,
    THERE_IS_NO_OTHER_LEADER,
    OPERATION_IS_INVALID_FOR_TEAM_ROLE_OF_ALTERED,
    YOU_ARE_ALREADY_MEMBER_OF_A_TEAM
}
