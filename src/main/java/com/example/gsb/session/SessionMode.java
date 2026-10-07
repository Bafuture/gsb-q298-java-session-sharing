package com.example.gsb.session;

/**
 * 会话模式：
 * STICKY     —— 会话粘滞在创建它的节点上，该节点是唯一写者，本地缓存即可保证一致；
 * NON_STICKY —— 任意节点都可读写，必须经由中心存储做版本 CAS 保证强一致。
 */
public enum SessionMode {
    STICKY,
    NON_STICKY
}
