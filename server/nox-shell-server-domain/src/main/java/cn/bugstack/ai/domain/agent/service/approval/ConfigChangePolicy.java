package cn.bugstack.ai.domain.agent.service.approval;

import cn.bugstack.ai.domain.ssh.service.policy.ShellCommandAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 限定已有 nginx/docker 配置；用户审核旧/新全文，执行时校验旧内容并备份、原子替换。 */
public final class ConfigChangePolicy {
    private ConfigChangePolicy() { }
    private static final String SCRIPT="""
        import base64,json,pathlib,os,stat,tempfile,shutil
        a=json.loads(base64.b64decode(__import__('sys').argv[1]))
        p=pathlib.Path(a['path'])
        if p.resolve()!=p: raise RuntimeError('拒绝符号链接或非规范路径')
        s=p.stat()
        if not stat.S_ISREG(s.st_mode): raise RuntimeError('目标不是普通配置文件')
        expected=a['expected'].encode('utf-8'); data=a['content'].encode('utf-8')
        if p.read_bytes()!=expected: raise RuntimeError('配置已变化，请重新读取并发起确认；未写入')
        backup=p.with_name(p.name+'.nox-shell-'+a['id']+'.bak')
        with backup.open('xb') as b: b.write(expected); b.flush(); os.fsync(b.fileno())
        shutil.copystat(p,backup); os.chown(backup,s.st_uid,s.st_gid)
        temp=None
        try:
            with tempfile.NamedTemporaryFile(dir=p.parent,prefix='.nox-shell-',delete=False) as f:
                temp=f.name; f.write(data); f.flush(); os.fsync(f.fileno())
            os.chmod(temp,stat.S_IMODE(s.st_mode)); os.chown(temp,s.st_uid,s.st_gid)
            if p.resolve()!=p or p.read_bytes()!=expected: raise RuntimeError('配置或路径已变化，未替换')
            os.replace(temp,p); temp=None
            print('配置已更新；备份：'+str(backup))
        finally:
            if temp is not None: os.unlink(temp)
        """;
    public static String command(String id,String path,String expected,String content) {
        if(path==null || !path.startsWith("/") || path.indexOf('\0')>=0)
            throw new IllegalArgumentException("配置路径必须是绝对路径");
        if(java.util.Arrays.stream(path.split("/")).anyMatch(segment->segment.equals(".")||segment.equals("..")))
            throw new IllegalArgumentException("配置路径不能包含 . 或 .. 路径段");
        if(expected==null || content==null || expected.getBytes(StandardCharsets.UTF_8).length>65536 || content.getBytes(StandardCharsets.UTF_8).length>65536)
            throw new IllegalArgumentException("配置旧/新内容不能为空值且各不超过 64 KiB");
        if(expected.equals(content)) throw new IllegalArgumentException("配置内容没有变化");
        try {
            var payload=java.util.Map.of("id",id,"path",path,"expected",expected,"content",content);
            String encoded=Base64.getEncoder().encodeToString(new ObjectMapper().writeValueAsBytes(payload));
            return "command -p 'sudo' '-n' '--' '/usr/bin/python3' '-c' " + ShellCommandAnalyzer.shellQuote(SCRIPT) + " " + ShellCommandAnalyzer.shellQuote(encoded);
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("配置变更编码失败",e); }
    }
}
