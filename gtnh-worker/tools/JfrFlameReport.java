import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;

/** JDK 21 source-file tool. Streams a JFR; no third-party dependency or huge JSON dump. */
public class JfrFlameReport {
    static class Node {
        String name; long value;
        Map<String,Node> children = new LinkedHashMap<>();
        Node(String name) { this.name=name; }
        void add(List<String> path,long weight) {
            Node node=this; node.value+=weight;
            for(String part:path) { node=node.children.computeIfAbsent(part,Node::new); node.value+=weight; }
        }
        String json() {
            return "{\"name\":"+quote(name)+",\"value\":"+value+",\"children\":["
                +String.join(",", children.values().stream().sorted(Comparator.comparingLong((Node n)->n.value).reversed())
                    .map(Node::json).toList())+"]}";
        }
    }
    static String quote(String value) {
        StringBuilder out=new StringBuilder("\"");
        for(char c:value.toCharArray()) {
            if(c=='"'||c=='\\') out.append('\\').append(c);
            else if(c<32||c=='<'||c=='>'||c=='&') out.append(String.format("\\u%04x",(int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
    static String name(RecordedFrame frame) {
        return frame.getMethod().getType().getName()+"."+frame.getMethod().getName()+":"+frame.getLineNumber();
    }
    static String top(Map<String,Long> entries,int limit) {
        return "["+String.join(",",entries.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed())
            .limit(limit).map(e->"{\"name\":"+quote(e.getKey())+",\"value\":"+e.getValue()+"}").toList())+"]";
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("java JfrFlameReport.java recording.jfr output-directory");
        Path input=Path.of(args[0]), output=Path.of(args[1]); Files.createDirectories(output);
        Node all=new Node("Java execution samples"), worker=new Node("Worker query execution samples"), viewer=new Node("Viewer execution samples"), nativeStacks=new Node("Native samples (may include blocking system calls)"), waits=new Node("Recorded blocking durations (microseconds)");
        Map<String,Long> self=new HashMap<>(), inclusive=new HashMap<>(), threads=new HashMap<>(), blocked=new HashMap<>(), relevantBlocked=new HashMap<>(), allocations=new HashMap<>(), workerAllocations=new HashMap<>(), stages=new HashMap<>();
        Node relevantWaits=new Node("Amidst / Worker blocking durations (microseconds)");
        Map<String,Long> viewerSelf=new HashMap<>(), viewerInclusive=new HashMap<>();
        long samples=0, workerSamples=0, nativeSamples=0, truncated=0, pauseNanos=0, maxPauseNanos=0, pauses=0;
        java.time.Instant first=null,last=null;
        try(RecordingFile recording=new RecordingFile(input)) {
            while(recording.hasMoreEvents()) {
                RecordedEvent event=recording.readEvent(); String type=event.getEventType().getName();
                if(first==null||event.getStartTime().isBefore(first))first=event.getStartTime();
                if(last==null||event.getEndTime().isAfter(last))last=event.getEndTime();
                if(type.equals("jdk.GCPhasePause")) {
                    long duration=event.getDuration().toNanos(); pauseNanos+=duration; maxPauseNanos=Math.max(maxPauseNanos,duration);pauses++;
                }
                if(type.equals("jdk.ObjectAllocationSample")) {
                    allocations.merge(event.getClass("objectClass").getName(),event.getLong("weight"),Long::sum);
                }
                RecordedStackTrace stack=event.getStackTrace(); if(stack==null||stack.getFrames().isEmpty())continue;
                var frames=stack.getFrames();
                RecordedThread thread=event.hasField("sampledThread")?event.getThread("sampledThread"):event.getThread();
                String threadName=thread==null?"unknown":thread.getJavaName();
                List<String> path=new ArrayList<>(); path.add("thread: "+threadName);
                for(int i=frames.size()-1;i>=0;i--)path.add(name(frames.get(i)));
                boolean isWorker=frames.stream().anyMatch(f->f.getMethod().getType().getName().startsWith("amidst.gtnh.worker.BiomeWorkerServer")
                    && (f.getMethod().getName().equals("executeQueuedQueries")||f.getMethod().getName().equals("executeOnServerThread")));
                boolean isViewer=!isWorker&&frames.stream().anyMatch(f->f.getMethod().getType().getName().startsWith("amidst."));
                if(isWorker&&type.equals("jdk.ObjectAllocationSample"))workerAllocations.merge(event.getClass("objectClass").getName(),event.getLong("weight"),Long::sum);
                if(type.equals("jdk.NativeMethodSample")) { nativeSamples++;nativeStacks.add(path,1); }
                else if(type.equals("jdk.ExecutionSample")) {
                    samples++;all.add(path,1); threads.merge(threadName,1L,Long::sum);if(stack.isTruncated())truncated++;
                    if(isViewer){
                        viewer.add(path,1);viewerSelf.merge(name(frames.get(0)),1L,Long::sum);
                        Set<String> seenViewer=new HashSet<>();
                        for(var frame:frames)if(seenViewer.add(name(frame)))viewerInclusive.merge(name(frame),1L,Long::sum);
                    }
                    if(isWorker) {
                        workerSamples++;worker.add(path,1);self.merge(name(frames.get(0)),1L,Long::sum);
                        Set<String> seen=new HashSet<>();
                        for(var frame:frames) if(seen.add(name(frame)))inclusive.merge(name(frame),1L,Long::sum);
                        String stage=frames.stream().filter(f->f.getMethod().getType().getName().startsWith("amidst.gtnh.worker.")).findFirst().map(JfrFlameReport::name).orElse("unknown");
                        stages.merge(stage,1L,Long::sum);
                    }
                } else if(Set.of("jdk.ThreadPark","jdk.JavaMonitorEnter","jdk.JavaMonitorWait","jdk.SocketRead","jdk.SocketWrite").contains(type)) {
                    long micros=event.getDuration().toNanos()/1000;
                    List<String> waitPath=new ArrayList<>();waitPath.add(type);waitPath.addAll(path);waits.add(waitPath,micros);
                    blocked.merge(type+" | "+threadName+" | "+name(frames.get(0)),micros,Long::sum);
                    if(isViewer||isWorker){relevantWaits.add(waitPath,micros);relevantBlocked.merge(type+" | "+threadName+" | "+name(frames.get(0)),micros,Long::sum);}
                }
            }
        }
        String summary="{\"file\":"+quote(input.getFileName().toString())+",\"first\":"+quote(String.valueOf(first))+",\"last\":"+quote(String.valueOf(last))
            +",\"samples\":"+samples+",\"workerSamples\":"+workerSamples+",\"nativeSamples\":"+nativeSamples+",\"truncatedSamples\":"+truncated
            +",\"gcPauseCount\":"+pauses+",\"gcPauseMillis\":"+pauseNanos/1e6+",\"maxGcPauseMillis\":"+maxPauseNanos/1e6
            +",\"sampledThreads\":"+top(threads,40)+",\"workerSelfSamples\":"+top(self,40)+",\"workerInclusiveSamples\":"+top(inclusive,70)
            +",\"workerNearestStages\":"+top(stages,40)+",\"viewerSelfSamples\":"+top(viewerSelf,40)+",\"viewerInclusiveSamples\":"+top(viewerInclusive,70)+",\"blockingMicros\":"+top(blocked,40)+",\"amidstBlockingMicros\":"+top(relevantBlocked,40)+",\"sampledAllocationBytes\":"+top(allocations,30)+",\"workerSampledAllocationBytes\":"+top(workerAllocations,30)+"}";
        Files.writeString(output.resolve("summary.json"),summary);
        String trees="{\"all\":"+all.json()+",\"worker\":"+worker.json()+",\"viewer\":"+viewer.json()+",\"native\":"+nativeStacks.json()+",\"waits\":"+waits.json()+",\"amidstWaits\":"+relevantWaits.json()+"}";
        String html="""
<!doctype html><html lang="zh"><meta charset="utf-8"><title>GTNH JFR flame graph</title>
<style>body{font:14px system-ui;margin:24px;color:#ddd;background:#151923}h1{font-size:22px}button,select,input{font:inherit;margin:6px;padding:7px;background:#282f3e;color:#eee;border:1px solid #5a6475;border-radius:4px}#graph{width:100%;overflow:auto;background:#202531}svg text{fill:#141820;pointer-events:none;font:11px monospace}rect{stroke:#202531;stroke-width:1;cursor:pointer}#tip{min-height:42px;white-space:pre-wrap}p{max-width:1000px;line-height:1.6}.muted{color:#9ba9bd}</style>
<h1>GTNH / Viewer · JFR 火焰图</h1><p id="meta"></p>
<p class="muted">横轴宽度表示采样次数，不是时间顺序；执行栈占比是统计样本，不能直接当作整张瓦片耗时。等待图使用累计微秒，多线程时间可以重叠。采样未覆盖的短调用不会出现。点击放大，双击复位。</p>
<select id="scope"><option value="worker">Worker 查询执行栈</option><option value="all">全部 Java 执行栈</option><option value="viewer">Viewer 执行栈</option><option value="native">Native 样本（可能含系统阻塞）</option><option value="amidstWaits">Amidst / Worker 等待</option><option value="waits">所有线程等待</option></select><button id="reset">复位</button><input id="search" placeholder="搜索类名 / 方法"><span id="count"></span>
<div id="tip"></div><div id="graph"></div>
<script>
const trees=__TREES__, summary=__SUMMARY__;
const scope=document.querySelector('#scope'),graph=document.querySelector('#graph'),tip=document.querySelector('#tip'),search=document.querySelector('#search');
let focus=trees.worker.value?trees.worker:trees.all;scope.value=trees.worker.value?'worker':'all';
document.querySelector('#meta').textContent=summary.file+' | '+summary.first+' → '+summary.last+' | 样本 '+summary.samples+' | Worker '+summary.workerSamples+' | 截断 '+summary.truncatedSamples+' | GC pause '+summary.gcPauseMillis.toFixed(2)+' ms';
function draw(){graph.replaceChildren();const width=Math.max(900,graph.clientWidth), svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('width',width);let maxDepth=0;const query=search.value.toLowerCase();
function visit(n,x,w,d){if(w<0.7)return;maxDepth=Math.max(maxDepth,d);const g=document.createElementNS(svg.namespaceURI,'g'),r=document.createElementNS(svg.namespaceURI,'rect');r.setAttribute('x',x);r.setAttribute('y',d*22);r.setAttribute('width',w);r.setAttribute('height',21);let hash=0;for(const c of n.name)hash=(hash*31+c.charCodeAt(0))|0;r.setAttribute('fill',query&&n.name.toLowerCase().includes(query)?'#e86bd1':`hsl(${20+Math.abs(hash)%45} 78% ${53+Math.abs(hash)%20}%)`);g.append(r);if(w>36){const t=document.createElementNS(svg.namespaceURI,'text');t.setAttribute('x',x+4);t.setAttribute('y',d*22+15);t.textContent=n.name.slice(0,Math.floor((w-8)/6.7));g.append(t)}g.onmouseenter=()=>tip.textContent=n.name+'\n'+n.value+' '+((scope.value==='waits'||scope.value==='amidstWaits')?'µs':'samples')+' · '+(100*n.value/Math.max(1,trees[scope.value].value)).toFixed(2)+'% of scope';g.onclick=()=>{focus=n;draw()};svg.append(g);let offset=x;for(const c of n.children){const cw=w*c.value/Math.max(1,n.value);visit(c,offset,cw,d+1);offset+=cw}}
visit(focus,0,width,0);svg.setAttribute('height',(maxDepth+1)*22);svg.ondblclick=reset;graph.append(svg);document.querySelector('#count').textContent='当前节点：'+focus.value+((scope.value==='waits'||scope.value==='amidstWaits')?' µs':' samples')}
function reset(){focus=trees[scope.value];draw()}scope.onchange=reset;document.querySelector('#reset').onclick=reset;search.oninput=draw;window.onresize=draw;draw();
</script></html>
""";
        // Java text blocks interpret escapes; keep the tooltip JS newline escaped.
        html=html.replace("+'\n'+n.value", "+'\\n'+n.value");
        Files.writeString(output.resolve("flamegraph.html"),html.replace("__TREES__",trees).replace("__SUMMARY__",summary));
        System.out.println("samples="+samples+", workerSamples="+workerSamples+", gcPauseMillis="+pauseNanos/1e6);
        System.out.println(output.resolve("flamegraph.html").toAbsolutePath());
    }
}
