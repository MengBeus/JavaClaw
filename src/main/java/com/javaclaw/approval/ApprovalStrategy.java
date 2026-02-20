package com.javaclaw.approval;

public interface ApprovalStrategy {
    boolean approve(String toolName, String arguments);
}
