package cn.bugstack.ai.trigger.http;
import java.util.*;
/** 每段模型消息分别处理 delta/snapshot，汇总不会再次追加。 */
final class StreamTextBuffer {
    private final Map<String,StringBuilder> messages=new LinkedHashMap<>();
    private final Map<String,Integer> rounds=new HashMap<>();
    void accept(String source,String text,boolean partial){
        var buffer=messages.computeIfAbsent(source+"/"+rounds.getOrDefault(source,0),key->new StringBuilder());
        if(!partial)buffer.setLength(0);
        buffer.append(text);
    }
    void endMessage(String source){rounds.merge(source,1,Integer::sum);}
    String fullText(){return String.join("\n\n",messages.values().stream().map(StringBuilder::toString).toList());}
}
