package cn.bugstack.ai.test.infrastructure;

import cn.bugstack.ai.domain.agent.service.approval.ConfigChangePolicy;
import org.junit.Test;
import org.junit.Assume;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** 在已有本地镜像创建的离线临时容器内运行真实写入脚本，不挂载宿主目录。 */
public class ConfigWriteScriptTest {
    @Test public void realScriptChecksOriginalContentBacksUpAndRejectsSymlinks() throws Exception {
        String image=System.getProperty("walissh.approval.testImage");Assume.assumeTrue(image!=null&&!image.isBlank());
        var field=ConfigChangePolicy.class.getDeclaredField("SCRIPT");field.setAccessible(true);
        String encoded=Base64.getEncoder().encodeToString(((String)field.get(null)).getBytes(StandardCharsets.UTF_8));
        String harness="""
            import base64,pathlib,tempfile,json,sys,os
            code=base64.b64decode(sys.argv[1]).decode()
            with tempfile.TemporaryDirectory(prefix='walissh-config-test-') as d:
                p=pathlib.Path(d)/'daemon.json'; p.write_text('old\\n'); os.chmod(p,0o640)
                def run(path,old,new,id):
                    sys.argv=['test',base64.b64encode(json.dumps(dict(path=str(path),expected=old,content=new,id=id)).encode()).decode()]
                    exec(code,dict(__name__='__main__'))
                try: run(p,'stale','new','stale'); raise AssertionError('stale accepted')
                except RuntimeError: pass
                assert p.read_text()=='old\\n'
                run(p,'old\\n','new\\n','success')
                assert p.read_text()=='new\\n'
                assert p.with_name(p.name+'.walissh-success.bak').read_text()=='old\\n'
                assert p.stat().st_mode & 0o777 == 0o640
                link=pathlib.Path(d)/'link'; link.symlink_to(p)
                try: run(link,'new\\n','bad','symlink'); raise AssertionError('symlink accepted')
                except RuntimeError: pass
                assert p.read_text()=='new\\n'
                assert not list(pathlib.Path(d).glob('.walissh-*'))
            print('config script passed')
            """;
        var process=new ProcessBuilder("docker","run","--rm","--pull=never","--network","none","--entrypoint","python3",image,"-c",harness,encoded).redirectErrorStream(true).start();
        try {
            assertTrue("离线容器验证超时",process.waitFor(20,TimeUnit.SECONDS));
            String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertEquals(output,0,process.exitValue());assertTrue(output.contains("config script passed"));
        }finally{process.destroyForcibly();}
    }
}
