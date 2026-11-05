package cn.bugstack.ai.infrastructure.adapter.port;

import cn.bugstack.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH 会话管理器
 * 管理所有活跃的 SSH 连接
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/6/6 20:07
 */
@Slf4j
@Component
public class SshSessionPort implements ISshSessionPort {

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final JSch jsch = new JSch();

    /**
     * 建立 SSH 连接
     *
     * @param connectionId 连接ID
     * @param host         主机地址
     * @param port         端口
     * @param username     用户名
     * @param password     密码（密码认证时）
     * @param privateKey   私钥（密钥认证时）
     * @return 是否连接成功
     */
    public boolean connect(String connectionId, String host, int port, String username,
                           String password, String privateKey) {
        // 如果已连接，先断开
        disconnect(connectionId);

        try {
            Session session = jsch.getSession(username, host, port);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setConfig("ServerAliveInterval", "30");   // 每30秒发送keep-alive
            session.setConfig("ServerAliveCountMax", "3");     // 3次无响应才断开
            session.setTimeout(0); // 不设置socket超时，避免reader线程被误杀

            if (privateKey != null && !privateKey.isEmpty()) {
                // 私钥认证
                jsch.addIdentity(connectionId, privateKey.getBytes(), null, null);
            } else if (password != null && !password.isEmpty()) {
                // 密码认证
                session.setPassword(password);
            } else {
                log.error("SSH连接失败：未提供认证信息 connectionId={}", connectionId);
                return false;
            }

            session.connect();
            sessions.put(connectionId, session);
            log.info("SSH连接成功 connectionId={} host={}:{} user={}", connectionId, host, port, username);
            return true;
        } catch (JSchException e) {
            log.error("SSH连接失败 connectionId={} host={}:{} error={}", connectionId, host, port, e.getMessage());
            return false;
        }
    }

    /**
     * 断开 SSH 连接
     *
     * @param connectionId 连接ID
     */
    public void disconnect(String connectionId) {
        Session session = sessions.remove(connectionId);
        if (session != null && session.isConnected()) {
            session.disconnect();
            log.info("SSH连接已断开 connectionId={}", connectionId);
        }
    }

    @Override
    public Set<String> getActiveConnectionIds() {
        // 返回快照，避免遍历期间并发修改
        return new HashSet<>(sessions.keySet());
    }

    /**
     * 检查连接是否活跃
     *
     * @param connectionId 连接ID
     * @return 是否已连接
     */
    public boolean isConnected(String connectionId) {
        Session session = sessions.get(connectionId);
        return session != null && session.isConnected();
    }

    /**
     * 获取会话
     *
     * @param connectionId 连接ID
     * @return JSch Session
     */
    public Session getSession(String connectionId) {
        return sessions.get(connectionId);
    }
}
