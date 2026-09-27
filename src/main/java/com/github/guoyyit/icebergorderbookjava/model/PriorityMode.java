package com.github.guoyyit.icebergorderbookjava.model;

/**
 * Time-priority behaviour on amend (replace):
 * KEEP  - keep original queue position (restricted amend);
 * LOSE  - lose time priority, re-enter at tail of price level.
 */
public enum PriorityMode {
    KEEP, LOSE
}
