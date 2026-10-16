#!/usr/bin/env python3
"""Patch only chapter 3 in the provided template; all other ZIP parts stay byte-identical."""
import argparse, copy, hashlib, json, pathlib, zipfile
from lxml import etree as E
P=argparse.ArgumentParser();P.add_argument('--run',default='resumed-20261014');a=P.parse_args()
BASE=pathlib.Path(__file__).resolve().parents[1];R=BASE/'results'/a.run
REF=pathlib.Path('/Users/starain/Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files/wxid_70eu39w9moe712_f3b5/temp/drag/附件二---作品设计报告（模板）.docx')
OUT=BASE/'document/作品设计报告_第三部分_完整实测版.docx'
A=json.loads((R/'analysis.json').read_text());assert A['profile']=='full' and A['complete'],'Require completed real experiment evidence'
ENV=json.loads((R/'environment.json').read_text());M=A.get('metrics',{});T=A.get('timings',{});POL=json.loads((R/'policies.json').read_text())
W='http://schemas.openxmlformats.org/wordprocessingml/2006/main';ns={'w':W}
def q(n):return '{'+W+'}'+n
def x(n,**attrs):return E.Element(q(n),{q(k):str(v) for k,v in attrs.items()})
def p(text,level=0,center=False,bold=False):
 e=x('p');pr=x('pPr');e.append(pr)
 if level:pr.append(x('pStyle',val='2' if level==1 else '3'))
 if level or bold:pr.append(x('keepNext'))
 pr.append(x('widowControl'));pr.append(x('snapToGrid',val='0'))
 pr.append(x('spacing',before=180 if level else 0,after=100 if level else 90,line=360,lineRule='auto'))
 pr.append(x('ind',firstLine=0 if level or center or bold else 480))
 if center:pr.append(x('jc',val='center'))
 r=x('r');rp=x('rPr');r.append(rp)
 rp.append(x('rFonts',ascii='Times New Roman',hAnsi='Times New Roman',eastAsia='黑体' if level else '宋体'))
 rp.append(x('sz',val=30 if level==1 else 28 if level==2 else 24));rp.append(x('color',val='000000'))
 if level or bold:rp.append(x('b'))
 t=x('t');t.set('{http://www.w3.org/XML/1998/namespace}space','preserve');t.text=text;r.append(t);e.append(r);return e
nodes=[]
def para(text,**kw):nodes.append(p(text,**kw))
def head(text):para(text,level=2)
def sub(text):para(text,bold=True)
def table(title,headers,rows,widths):
 caption=p(title,center=True);caption.find(q('pPr')).append(x('keepNext'));nodes.append(caption)
 t=x('tbl');pr=x('tblPr');t.append(pr);pr.append(x('tblW',w=9640,type='dxa'));pr.append(x('tblLayout',type='fixed'))
 borders=x('tblBorders')
 for side in ['top','left','bottom','right','insideH','insideV']:borders.append(x(side,val='single',sz=4,color='D9D9D9'))
 pr.append(borders);grid=x('tblGrid');t.append(grid)
 ww=[int(9640*v/sum(widths)) for v in widths]
 for v in ww:grid.append(x('gridCol',w=v))
 for ri,row in enumerate([headers]+rows):
  tr=x('tr');rp=x('trPr');rp.append(x('cantSplit'))
  if ri==0:rp.append(x('tblHeader'))
  tr.append(rp)
  for j,txt in enumerate(row):
   c=x('tc');cp=x('tcPr');cp.append(x('tcW',w=ww[j],type='dxa'));cp.append(x('vAlign',val='center'))
   mar=x('tcMar')
   for side in ['top','left','bottom','right']:mar.append(x(side,w=80,type='dxa'))
   cp.append(mar)
   if ri==0:cp.append(x('shd',fill='E7E6E6',val='clear'))
   c.append(cp);pp=p(str(txt),center=(j==0 or len(str(txt))<16),bold=ri==0)
   pp.find(q('pPr')).find(q('spacing')).set(q('line'),'280');pp.find(q('pPr')).find(q('spacing')).set(q('after'),'0')
   
   if ri<3 or ri==len(rows)-1:pp.find(q('pPr')).append(x('keepNext'))
   pp.find(q('pPr')).find(q('ind')).set(q('firstLine'),'0');pp.find('.//'+q('sz')).set(q('val'),'21')
   c.append(pp);tr.append(c)
  t.append(tr)
 nodes.append(t)
def fmt_metric(key):
 v=M.get(key)
 return '待回填' if not v else f"{v['numerator']}/{v['denominator']}（{v['percent']:.2f}%）"
def ms(key,field):return '待回填' if key not in T else f"{T[key][field]:.2f}"
para('3.系统测试与结果',level=1)
passed=A['acceptancePassed']
para('本章在100辆真实注册的模拟车辆环境下，对属性密钥生成、车辆注册、数据加密、云端转换、本地解密、双向属性验证、密文撤销和泄露密钥追踪开展实验。'+('本次连续运行段及各项功能测试均达到本章规定的验收门槛。' if passed else '本次存在未达到验收门槛的项目，结果按实际记录列示。')+'全部功能比例与耗时均由原始记录计算，不采用此前阶段报告的性能估算值。实验结论适用于列明的参数、负载及攻击样本模型，不能替代协议安全证明。')
head('3.1 测试方案')
sub('3.1.1 实验环境及参数')
para('实验于2026年10月13日至14日进行。主机为Apple M5（10个逻辑核心）、16 GiB内存；软件环境为macOS 26.4.1 ARM64、OpenJDK 1.8.0_492和JPBC 2.0.0。数据拥有者A、云服务器B、数据用户C及Key Curator D分别运行于独立Java进程，各进程最大堆内存2 GiB，通过本机回环HTTP通信。采用项目既有Type A配对参数和AES-GCM数据封装，明文载荷为1024字节。该部署属于单机四进程实验，不等同于四台物理设备或无线车联网实测。')
para('规模环境设置128个注册槽位和50个属性A1—A50，完成100辆模拟车辆的真实注册；32槽位演示环境保持独立。初次实验复用兼容的公共参数文件，车辆密钥则重新生成，并通过D端公钥校验与分层RegPK完成注册。Setup缓存加载不纳入KeyGen或注册计时，本章不评价冷启动公共参数生成耗时。')
para('100辆车辆分为四组，每组25辆：A端完整属性组、A端缺失A1组、C端完整属性组、C端缺失A1组。完整组具有A1—A50，缺失组具有A2—A50。所有测试策略均要求A1，以建立明确的合法与非法属性边界。数据恢复由50辆C端车辆承担；100个已注册身份作为系统背景规模，不表述为100个接收端同时请求。')
sub('3.1.2 策略覆盖与样本组织')
para('构造10种不同访问策略，分别包含5、10、15、20、25、30、35、40、45、50个不同属性。每个属性在表达式中仅出现一次，对应相同数量的访问矩阵行。C(m)表示A1至Am的全合取；对于偶数m，G(m)表示必须包含A1，并对A2与A3、A4与A5等相邻属性对分别取OR，最后与Am取AND。设计覆盖全AND与AND/OR混合结构；完整表达式保存在policies.json。')
table('表3-1 策略覆盖与本次连续运行段样本',['策略','属性数／矩阵行数','结构','完成次数'],[[r['id'],str(r['rows']),'C(%d)'%r['rows'] if r['structure']=='AND' else 'G(%d)'%r['rows'],str(r['completed'])] for r in A['perPolicy']],[1,2,2,2])
para('连续链路每次生成带唯一序号的新明文和新密文，10种策略交错执行，不通过重复解密同一密文增加加密次数，也不通过重试覆盖失败记录。分类恢复测试对每种策略遍历全部50辆接收车辆，共250次合法与250次非法判定；双向组合另设52组；撤销执行100轮，每轮20个目标；追踪构造100组有效泄露样本。撤销抽样使用固定种子20261013，密码运算中的密钥及加密随机性仍由密码随机源提供。')
sub('3.1.3 中断续跑及验收判定')
para('10月13日已完成100辆注册和361条完整链路，随后主动停止。10月14日核对程序JAR摘要一致后，复制隔离运行状态，从编号362继续执行，保留原始日志。为避免把跨日累计次数误称为不间断测试，在累计达到1000次后再追加361次，使本次编号362—1361构成独立的1000次连续运行段。历史361次与本次1000次分别统计，累计1361次；连续性验收只使用后一个运行段。')
para('恢复成功须同时满足本地解密成功、AES-GCM完整性验证通过、恢复明文与输入逐字节相同。正确执行率以正确完成数除以该运行段尝试数计算，1000次要求至少999次正确。合法恢复率和非法恢复率分别计算；网络异常、超时和未知错误不计为正确拒绝。被撤销车辆须返回REVOKED且不恢复明文，未撤销合法车辆须恢复原文；追踪必须返回与真实来源一致的车辆编号。')
head('3.2 功能测试')
sub('3.2.1 密钥注册及数据共享')
para(f"注册阶段成功完成{M['registration']['numerator']}/{M['registration']['denominator']}辆。历史完整链路为361条，本次连续段正确完成{M['uninterruptedPipeline']['numerator']}/{M['uninterruptedPipeline']['denominator']}次；累计链路结果为{fmt_metric('pipeline')}。每种策略在本次连续段中均有100次新加密与后续恢复样本。")
para(f"独立分类测试中，满足策略的恢复结果为{fmt_metric('authorizedRecovery')}；不满足策略的恢复结果为{fmt_metric('unauthorizedRecovery')}。非法集合均缺失必要属性A1，结果评价的是本组策略边界，不代表已经遍历所有非法属性集。连续链路计时与分类拒绝计时分开，避免快速拒绝路径拉低合法请求平均耗时。")
sub('3.2.2 双向属性验证')
para('分别控制发送方是否满足接收方验证策略、接收方是否满足发送策略。双方均满足、仅发送方满足、仅接收方满足、双方均不满足各设置13组不同身份对，共52组。前三类可分别检验正常通过及单侧拒绝；双方均不满足时，允许系统在先执行的一侧校验处拒绝。正确性以最终访问决策及明文恢复结果判定。')
bi=M['bilateral']
table('表3-2 双向属性组合实测结果',['组合','请求数','正确决策数','正确决策率','预期决策'],[[label,str(bi[str(i)]['denominator']),str(bi[str(i)]['numerator']),f"{bi[str(i)]['percent']:.2f}%",'恢复原文' if i==0 else '拒绝'] for i,label in enumerate(['双方均满足','仅发送方满足','仅接收方满足','双方均不满足'])],[2.1,1,1.3,1.4,1.3])
para('本测试通过正常接口选取真实身份与属性，评价双向条件的功能判定。它没有模拟不知道认证私钥的攻击者构造伪造证明，不能直接作为抵抗主动发送方冒充的证据，相关协议边界见第3.4.3节。')
sub('3.2.3 密文撤销')
para('在100辆已注册车辆背景下，每轮从25辆满足策略的C端车辆中随机选取20辆，对一条新密文依次提交20次目标撤销更新；每次要求密文版本递增1。随后验证全部20辆目标车及5辆保留权限车。100轮使用100条独立密文，循环覆盖10种策略，每种策略10轮；每轮目标不重复，轮间允许重复。')
para(f"共完成{M['revocationRounds']}轮实验、{M['revocationOperations']['denominator']}次目标更新，覆盖{M['distinctRevoked']}辆不同撤销目标。更新正确执行结果为{fmt_metric('revocationOperations')}，撤销车辆拒绝结果为{fmt_metric('revokedDenial')}，未撤销合法车辆恢复结果为{fmt_metric('remainingRecovery')}。该统计以完整轮次和逐次访问判定为依据，不将100次单车更新替代100轮实验。")
para('本项测试是指定密文的密码学撤销，并非用车辆注销后的接口权限拦截代替。撤销仅对当前更新版本作出结论，不能收回已交付的明文，也不能使已保存的旧密文、旧辅助项及旧私钥组合自动失效。')
sub('3.2.4 有效泄露密钥追踪')
para('从50辆C端车辆的真实私钥生成100组有效标量副本，每辆提供2组。场景标签为密钥复制34组、泄露33组、非法共享33组，分别表示授权私钥的不同流出方式；三类输入在密码学形式上相同。B端和D端各发起50次追踪，返回编号与脚本保存的真实来源编号比较。云端Trace遍历注册公钥执行匹配和方程验证，不直接使用样本自报来源作为识别答案。')
para(f"有效样本来源识别结果为{fmt_metric('trace')}，覆盖{M['distinctTracedIdentities']}个真实身份。本结果支持有效白盒密钥副本的来源识别，不扩展为黑盒盗版解密器追踪、多用户合谋生成的新密钥识别或任意密钥变换攻击的成功率。")
head('3.3 性能测试')
sub('3.3.1 计时范围与负载')
para('KeyGen、注册、加密、转换、本地解密及Trace采用后端纳秒时钟差值换算为毫秒；完整链路另由测试脚本使用单调时钟测量。KeyGen包括各层密钥生成和序列化；注册包括公钥校验及触发的满层聚合；加密包括对称封装、各已启用层的正向密文和发送证明；转换包括双向策略判定与相应密码运算；本地解密包括恢复及AES-GCM验证。后端阶段时间不包含全部网络往返、JSON处理和持久化开销，阶段之和不能替代端到端耗时。')
para('性能统计中，注册采用10月13日的100个实测样本；加密、转换、本地解密和端到端链路采用10月14日本次连续运行段，历史361次不混入其均值。非法属性快速拒绝不参与合法转换平均耗时，缺失计时字段不补零。统计给出样本数、均值、中位数、P95和最大值，P95取升序样本中序号为0.95倍样本数向上取整的位置。')
para('测试并发度为1，采用100辆注册背景下持续单请求流运行，重点评价持续执行正确性和密码阶段开销。策略交错执行，不删除首批样本或离群峰值。结果不代表多车同时请求的最大吞吐量；测试门槛未设绝对时延上限，因此耗时作为性能描述，不额外宣称满足某一毫秒级实时标准。')
keys=[('密钥生成','registration.keygenMs'),('车辆注册','registration.registrationMs'),('加密','continuous.encryptMs'),('云端转换','continuous.transformMs'),('本地解密','continuous.decryptMs'),('完整链路','pipeline.wallMs'),('撤销更新往返','revoke.wallMs'),('Trace计时','trace.traceMs')]
table('表3-3 各阶段耗时统计 单位ms',['阶段','样本数','均值','中位数','P95','最大值'],[[label,str(T[k]['n'])]+[ms(k,f) for f in ['mean','median','p95','max']] for label,k in keys],[1.8,1,1.3,1.3,1.3,1.3])
table('表3-4 不同策略的平均耗时 单位ms',['策略','矩阵行数','样本数','加密','云端转换','本地解密'],[[r['id'],str(r['rows']),str(r['completed'])]+[f"{r[f]:.2f}" for f in ['encryptMs','transformMs','decryptMs']] for r in A['perPolicy']],[1,1.3,1,1.7,1.7,1.7])
para(f"本次完整链路平均耗时为{ms('pipeline.wallMs','mean')} ms，P95为{ms('pipeline.wallMs','p95')} ms，最大值为{ms('pipeline.wallMs','max')} ms。注册平均耗时为{ms('registration.registrationMs','mean')} ms、最大值为{ms('registration.registrationMs','max')} ms，应结合分层满块聚合解释峰值。表3-4同时改变策略行数和AND/OR结构，其差异是本组策略的综合实测效应，不宜单独归因于行数。")
head('3.4 测试数据与结果')
sub('3.4.1 验收汇总')
table('表3-5 验收指标与实测结果',['指标','门槛','实测结果'],[['真实注册','不少于100辆',fmt_metric('registration')],['策略覆盖','不少于10种，5—50行','10种，5—50行'],['本次连续链路','不少于1000次，≥99.9%',fmt_metric('uninterruptedPipeline')],['合法恢复','100%',fmt_metric('authorizedRecovery')],['非法恢复','0%',fmt_metric('unauthorizedRecovery')],['双向合法通过','100%','%d/%d'%(bi['0']['numerator'],bi['0']['denominator'])],['撤销轮数','不少于100轮',str(M['revocationRounds'])],['不同撤销车辆','不少于20辆',str(M['distinctRevoked'])],['撤销后拒绝','100%',fmt_metric('revokedDenial')],['未撤销合法恢复','100%',fmt_metric('remainingRecovery')],['有效副本追踪','不少于100组，100%',fmt_metric('trace')]],[2.2,2.5,3.2])
para(('在所列实验模型下，全部规模与功能指标达到验收要求。' if passed else '本次未达到全部验收要求，应按各指标实际分子、分母定位未通过项。')+'观察到的100%仅是本次有限样本的经验比例，不是长期成功率下界或密码学安全性的数学证明。既有阶段报告中的时间外推保留为历史材料，本章结论全部以完成后的实测记录为准。')
sub('3.4.2 证据留存与复现')
para('测试代码及数据存放于项目validation_20261013目录。results/full-01保留10月13日中止时的原始记录与状态；results/resumed-20261014保存续跑证据。续跑records.jsonl首先逐字节复制原有记录，再追加带segment=resumed标记的新记录。continuous与pipeline分别是同一链路的阶段和整体计时，不能相加计算样本量。environment.json保存程序摘要、运行参数及原始记录摘要；groups.json、policies.json和revocation_targets.json保存分组、策略与撤销目标。')
para('续跑完整原始记录SHA-256摘要如下：')
para(A['evidenceSha256'],center=True)
para('scripts/resume_acceptance.py用于复制已保存的注册状态并执行续跑，scripts/analyze.py从逐条记录重新计算结果；analysis.json为本章汇总依据。程序版本在两次运行间保持一致，注册样本来自前一日，主要链路性能来自续跑当日。私钥及节点令牌保存在受限的private-runtime和config目录，公开复核材料使用测试代码、去标识记录与汇总。')
sub('3.4.3 安全边界与后续工作')
para('本章是实现正确性及给定负载下的工程验证，不构成方案的形式化安全证明。项目安全分析已记录发送认证等式的代数可塑性问题。正常身份组合通过以及非法属性被拒绝，都不能排除攻击者在不知道认证私钥时构造伪造证明；因此本章不将双向功能验证等同于抗任意发送方冒充。该问题需协议修订及独立安全论证。')
para('撤销结论限定于当前密文更新版本，追踪结论限定于有效白盒标量副本；不涵盖旧副本追溯失效、黑盒盗版解密器或合谋安全。本实验使用项目既有配对参数，不据此认定任何标准安全强度或密码合规等级。后续评估应在认证机制修订后补充跨主机通信、并发负载、故障恢复及主动攻击测试。')
with zipfile.ZipFile(REF) as z:
 xml=E.fromstring(z.read('word/document.xml'));body=xml.find(q('body'));children=list(body)
 def text(e):return ''.join(e.itertext()) if False else ''.join(e.xpath('.//w:t/text()',namespaces=ns))
 start=next(i for i,e in enumerate(children) if text(e)=='3.系统测试与结果')
 end=next(i for i,e in enumerate(children) if text(e)=='4.应用前景')
 for child in children[start:end]:body.remove(child)
 for i,node in enumerate(nodes):body.insert(start+i,node)
 OUT.parent.mkdir(exist_ok=True,parents=True)
 with zipfile.ZipFile(OUT,'w',zipfile.ZIP_DEFLATED) as out:
  for info in z.infolist():out.writestr(copy.copy(info),E.tostring(xml,xml_declaration=True,encoding='UTF-8',standalone=True) if info.filename=='word/document.xml' else z.read(info.filename))
 with zipfile.ZipFile(OUT) as out:
  assert all(z.read(name)==out.read(name) for name in z.namelist() if name!='word/document.xml')
text_content='\n\n'.join(''.join(e.xpath('.//w:t/text()',namespaces=ns)) for e in nodes)
(OUT.parent/(OUT.stem+'.txt')).write_text(text_content,encoding='utf-8')
print(OUT)
