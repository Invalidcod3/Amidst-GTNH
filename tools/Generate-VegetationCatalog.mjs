// Rebuild the shared GTNH 2.9.0 / 2.9.1 vegetation index from locally audited sources.
// Inputs and limitations are documented in docs/reference/vegetation.md.
import fs from 'node:fs';
import path from 'node:path';
const audit = process.argv[2] || 'build/vegetation-audit';
const rwg2 = process.argv[3] || 'build/rwg2-reference/sources/rwg2';
const read = p => fs.readFileSync(p, 'utf8');
const clean = s => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
const files = p => fs.readdirSync(p, {withFileTypes:true}).flatMap(e => e.isDirectory() ? files(path.join(p,e.name)) : [path.join(p,e.name)]);
const java = p => files(p).filter(p=>p.endsWith('.java'));
const lang = (mod,l) => Object.fromEntries(read(`${audit}/${mod}-${l}.lang`).split(/\r?\n/).filter(x=>x.includes('=')).map(x=>[x.slice(0,x.indexOf('=')),x.slice(x.indexOf('=')+1)]));
const en = lang('BiomesOPlenty','en_US'), zh = lang('BiomesOPlenty','zh_CN');
const entries = new Map();
function entry(id,name,zhName,category,mod) {
    if (!entries.has(id)) entries.set(id,{id,name,zhName,category,mod,rules:[]});
    return entries.get(id);
}
function rule(e, r) { e.rules.push(r); }
function names(e, biomes, generators=['RWG','RWG2_CONTINENTS'], dimensions=['OVERWORLD'], source='') {
    if (biomes.length) rule(e,{names:[...new Set(biomes)].sort(),generators,dimensions,source});
}
const vanillaNames=['Poppy','Blue Orchid','Allium','Azure Bluet','Red Tulip','Orange Tulip','White Tulip','Pink Tulip','Oxeye Daisy','Dandelion','Sunflower','Lilac',null,null,'Rose Bush','Peony'];
const vanillaZh=['虞美人','兰花','绒球葱','蓝花美耳草','红色郁金香','橙色郁金香','白色郁金香','粉红色郁金香','滨菊','蒲公英','向日葵','丁香',null,null,'玫瑰丛','牡丹'];
const flowers=vanillaNames.map((n,i)=>n?entry(`minecraft:flower:${i}`,n,vanillaZh[i],'Flowers','Minecraft'):null);
const trees={};
for(const [key,n,z] of [['oak','Oak','橡树'],['spruce','Spruce','云杉'],['birch','Birch','白桦'],['jungle','Jungle tree','丛林树'],['acacia','Acacia','金合欢'],['dark_oak','Dark oak','深色橡树']]) trees[key]=entry(`minecraft:${key}`,n,z,'Trees','Minecraft');
const bopFlowers={};
for(const block of ['flowers','flowers2']) {
    Object.keys(en).filter(k=>k.startsWith(`tile.${block}.`)).forEach((key,i)=>{
        bopFlowers[`${block}:${i}`]=entry(`bop:${block}:${i}`,en[key],zh[key]||en[key],'Flowers',"Biomes O' Plenty");
    });
}
const leafTrees={};
for(const block of ['leaves1','leaves2','leaves3','leaves4','colorizedLeaves1','colorizedLeaves2','appleLeaves','persimmonLeaves']) {
    Object.keys(en).filter(k=>k.startsWith(`tile.${block}.`)||k===`tile.${block}.name`).forEach((key,i)=>{
        leafTrees[`${block}:${i}`]=entry(`bop:${block}:${i}`,en[key].replace(/ Leaves$/,''),(zh[key]||en[key]).replace(/树叶$/,'树'),'Trees',"Biomes O' Plenty");
    });
}
const bopFruitTrees=[leafTrees['appleLeaves:0'],leafTrees['persimmonLeaves:0'],entry('bop:fruit:peach','Peach-bearing tree','挂桃子的树','Trees',"Biomes O' Plenty"),entry('bop:fruit:pear','Pear-bearing tree','挂梨子的树','Trees',"Biomes O' Plenty")];
for(const e of bopFruitTrees){e.note='Includes natural trees with hanging BOP fruit, regardless of leaf-block type.';e.zhNote='包含挂有 BOP 果实的自然树，不要求树叶方块也是果树树叶。';}
const bopRoot=`${audit}/bop/biomesoplenty`;
const registry=read(`${bopRoot}/common/core/BOPBiomes.java`);
const bopNames={}, bopFields={};
for(const m of registry.matchAll(/BOPCBiomes\.(\w+)\s*=\s*BOPBiomes\.register\w+\((\w+)\.class,\s*"([^"]+)"/g)) {bopFields[m[1]]=m[3];bopNames[m[2]]=m[3];}
const eligible={RWG:new Set(),RWG2_CONTINENTS:new Set()};
const mapNames={};
// Keep independent generator maps: identical base names do not imply identical decorators.
for(const [gen,root] of [['RWG',`${audit}/alpha/rwg`],['RWG2_CONTINENTS',rwg2]]) {
    const base=clean(read(`${root}/biomes/base/BaseBiomes.java`));
    const baseNames=Object.fromEntries([...base.matchAll(/RWGBiomes\.(\w+)\s*=\s*new \w+\([^;]*?"([^"]+)"/g)].map(m=>[m[1],m[2]]));
    mapNames[gen]=baseNames;
    for(const n of Object.values(baseNames)) eligible[gen].add(n);
    const support=clean(read(`${root}/support/SupportBOP.java`));
    for(const m of support.matchAll(/BOPCBiomes\.(\w+)/g)) if(bopFields[m[1]]) eligible[gen].add(bopFields[m[1]]);
    for(const n of ['Magical Forest','Tainted Land']) eligible[gen].add(n);
    for(const f of java(`${root}/biomes/realistic`)) {
        const s=clean(read(f));
        const baseField=s.match(/super\(\s*\d+\s*,\s*RWGBiomes\.(\w+)/)?.[1];
        const biome=baseNames[baseField]; if(!biome)continue;
        const source=`${gen}/${path.basename(f)}`;
        for(const m of s.matchAll(/new DecoFlowers\(new int\[\]\s*\{([^}]+)\}/g)) for(const v of m[1].match(/\d+/g)||[]) if(flowers[+v])names(flowers[+v],[biome],[gen],['OVERWORLD'],source);
        const found=new Set();
        if(/new (?:Deco\w*Spruce|WorldGenTaiga|WorldGenMegaPine)/.test(s))found.add('spruce');
        for(const [c,a] of calls(s)) {
            if(c==='DecoPineTree') {found.add('oak'); if(/1\s*:/.test(a)||/,\s*1$/.test(a))found.add('spruce');}
            if(c==='DecoSmallPine') {if(/,\s*0$/.test(a))found.add('oak');else if(/,\s*1$/.test(a))found.add('spruce');else {found.add('oak');found.add('spruce');}}
            if(c==='DecoLargePine') {const m=a.match(/,\s*(\d+)\s*,\s*(\d+)\s*$/);if(m){found.add(['oak','spruce','birch','jungle'][+m[1]]);found.add(['oak','spruce','birch','jungle'][+m[2]]);}else found.add('spruce');}
        }
        if(/new (?:DecoBirch|WorldGenForest)/.test(s))found.add('birch');
        if(/new (?:DecoSavannah|WorldGenSavanna)/.test(s))found.add('acacia');
        if(/new (?:DecoJungle|WorldGenMegaJungle)/.test(s))found.add('jungle');
        if(/new (?:DecoOak|WorldGenBigTree)/.test(s))found.add('oak');
        // Trees(false) is oak; the five-argument jungle constructor carries log metadata 3.
        if(/new WorldGenTrees\(false\)/.test(s))found.add('oak');
        if(/new WorldGenTrees\(false,/.test(s))found.add(/,\s*3,\s*3,\s*true/.test(s)?'jungle':'oak');
        for(const m of s.matchAll(/new DecoShrub\(([^;]+?)\)\)/g)) {
            if(/,\s*3,/.test(m[1]))found.add('jungle');
            else {found.add('oak'); if(/nextInt\(2\)/.test(m[1]))found.add('spruce');}
        }
        for(const t of found)names(trees[t],[biome],[gen],['OVERWORLD'],source);
    }
}
function calls(s) {
    const out=[];const rx=/new (\w+)\(/g;let m;
    while((m=rx.exec(s))){let level=1,i=rx.lastIndex;for(;i<s.length&&level;i++){if(s[i]==='(')level++;else if(s[i]===')')level--;}
        out.push([m[1],s.slice(rx.lastIndex,i-1)]);}
    return out;
}
function leaf(block,meta) {
    if(block==='Blocks.field_150362_t')return [trees[['oak','spruce','birch','jungle'][+meta&3]]];
    if(block==='Blocks.field_150361_u')return [trees[+meta===0?'acacia':'dark_oak']];
    return [leafTrees[`${block.replace('BOPCBlocks.','')}:${meta}`]].filter(Boolean);
}
const special={WorldGenSacredOak:'colorizedLeaves1:0',WorldGenBayou3:'colorizedLeaves2:0',WorldGenPineTree:'colorizedLeaves2:1',WorldGenPalmTree1:'colorizedLeaves1:2',WorldGenMangrove:'colorizedLeaves1:1',WorldGenDeadTree:'dead',WorldGenBogBush:'oak',WorldGenBrush1:'jungle',WorldGenChaparral3:'oak',WorldGenPoplar:'birch',WorldGenPoplar2:'birch',WorldGenTropicsShrub:'colorizedLeaves1:2'};
function treeEntries(s) {
    const result=new Set();
    for(const [c,args] of calls(s)) {
        const a=args.replace(/\(Block\)/g,'').split(',').map(x=>x.trim());
        if(a[1]?.match(/^(?:BOPCBlocks|Blocks)\./)&&/^\d+$/.test(a[3])) for(const e of leaf(a[1],a[3]))result.add(e);
        if(c==='WorldGenMixedTree'&&a[4])for(const e of leaf(a[4],a[5]))result.add(e);
        const fruit=c==='WorldGenOriginalTree'&&a.length===5?a[4]:c==='WorldGenMixedTree'&&a.length===7?a[6]:null;
        if(fruit!==null&&bopFruitTrees[fruit])result.add(bopFruitTrees[fruit]);
        if(c==='WorldGenShrub')result.add(trees[['oak','spruce','birch','jungle'][+a[1]]]);
        if(c==='WorldGenBOPJungle')result.add(trees[['oak','spruce','birch','jungle'][+a[4]]]);
        if(c==='WorldGenTrees')result.add(trees[a.length===1?'oak':+a[3]===3?'jungle':'oak']);
        if(c.startsWith('WorldGenTaiga'))result.add(trees.spruce);
        if(c==='WorldGenForest')result.add(trees.birch);
        if(c==='WorldGenBigTree')result.add(trees.oak);
        const t=special[c];if(t)result.add(t==='dead'?entry('bop:dead','Dead tree','枯树','Trees',"Biomes O' Plenty"):trees[t]||leafTrees[t]);
    }
    if(/field_7675[78]_/.test(s))result.add(trees.oak);
    return [...result].filter(Boolean);
}
for(const f of java(`${bopRoot}/common/biome`)) {
    const biome=bopNames[path.basename(f,'.java')];if(!biome)continue;
    const s=clean(read(f)), dimension=f.includes(`${path.sep}nether${path.sep}`)?'NETHER':f.includes(`${path.sep}end${path.sep}`)?'END':'OVERWORLD';
    const gens=dimension==='OVERWORLD'?Object.keys(eligible).filter(g=>eligible[g].has(biome)):Object.keys(eligible);
    if(!gens.length)continue;
    const source=`BiomesOPlenty/${path.basename(f)}`;
    const add=e=>e&&names(e,[biome],gens,[dimension],source);
    const flowerCount=+(s.match(/field_76802_A\s*=\s*(-?\d+)/)?.[1]||0);
    if(flowerCount>0){add(flowers[0]);add(flowers[9]);}
    if(/bopFlowersPerChunk\s*=\s*[1-9]/.test(s)) {
        for(const [c,a] of calls(s)) {
            if(c==='WorldGenBOPFlora') {
                const m=a.match(/BOPCBlocks\.(flowers2?)\s*,\s*(\d+)/);if(m)add(bopFlowers[`${m[1]}:${m[2]}`]);
                const v=a.match(/Blocks\.field_150328_O,\s*(\d+)/);if(v)add(flowers[+v[1]]);
            }
            if(c==='WorldGenBOPDoubleFlora'&&/^\d/.test(a))add(flowers[10+parseInt(a)]);
            if(c==='WorldGenBOPDoubleFlora'&&/flowers,.*13/.test(a))add(bopFlowers['flowers:13']);
        }
    }
    if(dimension==='OVERWORLD'&&!/minersDelightPerChunk\s*=\s*0/.test(s))add(bopFlowers['flowers2:6']);
    if(/lavenderPerChunk\s*=\s*[1-9]/.test(s))add(bopFlowers['flowers2:3']);
    if(/bromeliadsPerChunk\s*=\s*[1-9]/.test(s))add(bopFlowers['flowers:12']);
    const treeMethod=s.match(/public WorldGenAbstractTree func_150567_a\(Random \w+\)\s*\{([\s\S]*?)\n    \}/)?.[1];
    const count=+(s.match(/field_76832_z\s*=\s*(-?\d+)/)?.[1]||0);
    if(count>=0&&(dimension==='OVERWORLD'||treeMethod))for(const e of treeEntries(treeMethod||'this.field_76757_N'))add(e);
    if(/new WorldGenGiantFlower/.test(treeMethod||''))for(const [id,n,z] of [['red','Giant Red Flower','巨型红花'],['yellow','Giant Yellow Flower','巨型黄花']])add(entry(`bop:giant_${id}`,n,z,'Flowers',"Biomes O' Plenty"));
}
// BOP overrides Hell in GTNH 2.9.0 (biomegen.cfg: Hell=true).
names(bopFlowers['flowers2:2'],['Hell'],Object.keys(eligible),['NETHER'],'BiomesOPlenty/BiomeGenBOPHell weightedFlowerGen; bopFlowersPerChunk=4');
// Oasis river-bank edits supplement the original BOP decorator in both generators.
for(const e of [trees.oak,trees.acacia,...[9,3,2,11].map(i=>flowers[i])])names(e,['Brushland','Heathland','Outback'],Object.keys(eligible),['OVERWORLD'],'RWG and RWG2/support/edit/EditRiverOasis; river strength > 0.7');
// Tag predicates are disjunctive normal form: any clause may match; all literals in a clause must match.
function tags(e,clauses,source,dimensions=['OVERWORLD','TWILIGHT_FOREST']) {rule(e,{tags:clauses,generators:Object.keys(eligible),dimensions,source});}
// Et Futurum 2.6.65: flowers and cherry trees are enabled in the 2.9.0 config.
// The additional exclusions come from Utils.excludeBiomesFromTypesWithDefaults.
const cherryTags=[['MOUNTAIN','!SNOWY','!HOT','!SANDY','!MESA','!SPARSE','!JUNGLE','!NETHER','!END','!DEAD','!SPOOKY','!WASTELAND']];
tags(entry('etfuturum:cornflower','Cornflower','矢车菊','Flowers','Et Futurum Requiem'),[['PLAINS','!SAVANNA','!SNOWY']],'EtFuturumWorldGenerator.postInit/generate');
tags(entry('etfuturum:lily_of_the_valley','Lily of the Valley','铃兰','Flowers','Et Futurum Requiem'),[['FOREST','!JUNGLE','!DRY','!HOT','!SNOWY','!COLD']],'EtFuturumWorldGenerator.postInit/generate');
tags(entry('etfuturum:cherry','Cherry tree','樱花树','Trees','Et Futurum Requiem'),cherryTags,'EtFuturumWorldGenerator: cherryTreeRarity=72');
const petals=entry('etfuturum:pink_petals','Pink Petals','粉红色花簇','Flowers','Et Futurum Requiem');
tags(petals,cherryTags,'EtFuturumWorldGenerator.postInit/generate');
petals.note='Placed around successful cherry trees; also registered in eligible biomes\' flower lists.';
petals.zhNote='随成功生成的樱花树在周围放置，也加入对应群系的花朵列表。';
const gardens={berry:[['FOREST'],['HILLS'],['COLD'],['WET']],desert:[['SANDY']],grass:[['PLAINS','!COLD']],gourd:[['PLAINS'],['FOREST']],ground:[['MOUNTAIN'],['HILLS'],['MESA'],['SAVANNA']],herb:[['FOREST','!COLD'],['WET']],leafy:[['FOREST','!COLD'],['WET']],mushroom:[['FOREST','!COLD','!MOUNTAIN'],['WET']],stalk:[['PLAINS','!COLD'],['FOREST','!COLD'],['WET']],textile:[['COLD'],['HILLS'],['WET']],tropical:[['WET'],['HOT']],water:[['OCEAN','!COLD'],['RIVER','!COLD']]};
const gardenZh={berry:'浆果菜园',desert:'沙漠菜园',grass:'草本菜园',gourd:'瓜类菜园',ground:'根茎菜园',herb:'香草菜园',leafy:'叶菜菜园',mushroom:'蘑菇菜园',stalk:'茎秆菜园',textile:'纺织菜园',tropical:'热带菜园',water:'水生菜园'};
for(const [g,c]of Object.entries(gardens))tags(entry(`harvestcraft:${g}garden`,`${g[0].toUpperCase()+g.slice(1)} Garden`,gardenZh[g],'Gardens',"Pam's HarvestCraft"),c.map(x=>[...x,'!DEAD']),'HarvestCraft/PamGardenGenerator.generateSurface');
const fruitEn=lang('harvestcraft','en_US'),fruitZh=lang('harvestcraft','zh_CN');
const temperate=['apple','avocado','cherry','chestnut','nutmeg','pear','walnut','plum','gooseberry'];
const tropical=['banana','cinnamon','coconut','date','dragonfruit','papaya','almond','apricot','cashew','durian','fig','grapefruit','lemon','lime','mango','orange','paperbark','peach','pecan','peppercorn','persimmon','pistachio','pomegranate','starfruit','vanillabean'];
for(const g of [...temperate,...tropical,'maple','olive']) {
    const key=Object.keys(fruitEn).find(k=>k.toLowerCase().includes(g+'sapling')&&k.endsWith('.name'));
    const e=entry(`harvestcraft:${g}`,key?fruitEn[key].replace(/ Sapling$/,''):g,key?(fruitZh[key]||g).replace(/树苗$/,'树'):g,'Fruit trees',"Pam's HarvestCraft");
    const trop=[['WET','!PLAINS','!DRY','!DEAD'],['HOT','!PLAINS','!DRY','!DEAD']];
    if(temperate.includes(g))tags(e,[['FOREST','!COLD','!SPOOKY','!MOUNTAIN','!DEAD']],'HarvestCraft/PamTreeGenerator.generateSurface');
    if(tropical.includes(g))tags(e,trop,'HarvestCraft/PamTreeGenerator.generateSurface');
    if(g==='coconut')tags(e,[['OCEAN','!DEAD']],'HarvestCraft/PamTreeGenerator ocean branch');
    if(g==='maple')tags(e,trop.map(x=>[...x,'CONIFEROUS']),'HarvestCraft/PamTreeGenerator nested maple branch');
    if(g==='olive'){e.note='Enabled in config, but no natural generation branch in HarvestCraft 1.3.15.';e.zhNote='配置虽开启，但 HarvestCraft 1.3.15 没有橄榄树自然生成分支。';}
}
// Natura uses exact biome names, not Forge tags. Preserve spaces and spelling verbatim.
const forest=['Forest','AutumnWoods','BirchForest','PineForest','Rainforest','TemperateRainforest','Woodlands'];
const natura={sakura:['Sakura','樱花树',[...forest,'River']],eucalyptus:['Eucalyptus','桉树',[...forest,'Plains','Meadow','Extreme Hills','Extreme Hills Edge','ForestedHills','GreenHills']],redwood:['Redwood','红杉',['Plains','Meadow']],hopseed:['Hopseed','啤酒花树',['Extreme Hills','Extreme Hills Edge','ForestedHills','GreenHills']],amaranth:['Amaranth','紫心木',['Jungle','JungleHills','Extreme Jungle']],maple:['Maple','枫树',['Forest','Woodlands','AutumnWoods']],silverbell:['Silverbell','银钟树',['Forest','Woodlands','AutumnWoods']],tigerwood:['Tigerwood','虎纹木',['Forest','Rainforest','TemperateRainforest']],willow:['Willow','柳树',['River','Swampland','ForestHills']]};
for(const [id,[n,z,b]]of Object.entries(natura))names(entry(`natura:${id}`,n,z,'Trees','Natura'),b,Object.keys(eligible),['OVERWORLD'],'Natura/BaseTreeWorldgen.generate');
for(const [id,n,z]of [['bloodwood','Bloodwood','血木'],['ghostwood','Ghostwood','幽灵木'],['darkwood','Darkwood','黑暗木'],['fusewood','Fusewood','熔融木']])names(entry(`natura:${id}`,n,z,'Trees','Natura'),['Hell','Boneyard','Phantasmagoric Inferno','Corrupted Sands'],Object.keys(eligible),['NETHER'],'Natura/BaseTreeWorldgen.generate');
tags(entry('natura:bluebells','Bluebells','蓝铃花','Flowers','Natura'),[[]],'Natura/BaseCropWorldgen: placement needs soil and light');
for(let i=0;i<9;i++)names(flowers[i],['Magical Forest'],Object.keys(eligible),['OVERWORLD'],'Thaumcraft/BiomeGenMagicalForest');
const greatwood=entry('thaumcraft:greatwood','Greatwood','宏伟之木','Trees','Thaumcraft');
const silverwood=entry('thaumcraft:silverwood','Silverwood','银树','Trees','Thaumcraft');
names(greatwood,['Magical Forest'],Object.keys(eligible),['OVERWORLD'],'Thaumcraft/BiomeGenMagicalForest tree selector');
names(silverwood,['Magical Forest'],Object.keys(eligible),['OVERWORLD'],'Thaumcraft/BiomeGenMagicalForest tree selector');
tags(greatwood,[['CONIFEROUS'],['FOREST'],['SAVANNA'],['PLAINS'],['SWAMP'],['LUSH'],['MAGICAL']],'Thaumcraft/Config biomeInfo and BiomeHandler');
names(trees.oak,['Magical Forest'],Object.keys(eligible),['OVERWORLD'],'Thaumcraft/WorldGenBigMagicTree uses vanilla oak blocks');
const cinder=entry('thaumcraft:cinderpearl','Cinderpearl','烈焰花','Flowers','Thaumcraft');
cinder.note='Requires biome topBlock=sand, temperature > 1 and height at/below world height. RWG Hot Desert has temperature 1, so its name alone does not qualify.';
cinder.zhNote='需群系 topBlock 为沙、温度严格大于 1，且生成高度不超过世界高度上限；RWG 的 Hot Desert 温度为 1，不能按沙漠名称推断。当前未核实到支持群系池中的匹配。';
tags(entry('ic2:rubber','Rubber tree','橡胶树','Trees','IndustrialCraft 2'),[['SWAMP'],['FOREST'],['JUNGLE']],'IC2.generate: rubberTree=true');
// Silverwood's global generator explicitly excludes Tainted Land; names are excluded per rule.
rule(silverwood,{tags:[['MAGICAL']],excludeNames:['Tainted Land'],dimensions:['OVERWORLD','TWILIGHT_FOREST'],generators:Object.keys(eligible),source:'Thaumcraft/ThaumcraftWorldGenerator.generateSilverwood'});
names(silverwood,['ForestHills','Birch Forest Hills'],Object.keys(eligible),['OVERWORLD'],'Thaumcraft/ThaumcraftWorldGenerator.generateSilverwood');
const shimmer=entry('thaumcraft:shimmerleaf','Shimmerleaf','闪耀之花','Flowers','Thaumcraft');
shimmer.rules=structuredClone(silverwood.rules);shimmer.note='Generated around successful silverwood trees; requires grass.';shimmer.zhNote='随成功生成的银树在周围生成，需草方块。';
const botania=entry('botania:mystical_flowers','Mystical flowers (16 colors)','神秘花（16 色）','Flowers','Botania');
tags(botania,[[]],'GTNH 2.9.0 Botania.cfg: dimension whitelist 64',['ROSS_128B']);
botania.note='GTNH restricts natural flowers to Ross 128b (dimension 64).';botania.zhNote='GTNH 将自然生成限定在罗斯 128b（维度 64）。';
// Twilight Forest uses its own decorators, independently of the selected overworld generator.
const tfRoot=`${audit}/tf/twilightforest/biomes`;
const tfBase=read(`${tfRoot}/TFBiomeBase.java`);
const tfNames=Object.fromEntries([...tfBase.matchAll(/new (TFBiome\w+)\([^;]+?func_76735_a\("([^"]+)"\)/g)].map(m=>[m[1],m[2]]));
const canopy=entry('twilightforest:canopy','Canopy tree','苍穹树','Trees','Twilight Forest');
const hollow=entry('twilightforest:oak','Twilight oak / hollow oak','暮色橡树／空心橡树','Trees','Twilight Forest');
const mangrove=entry('twilightforest:mangrove','Twilight mangrove','暮色红树','Trees','Twilight Forest');
const dark=entry('twilightforest:darkwood','Darkwood canopy','黑暗树','Trees','Twilight Forest');
const rainbow=entry('twilightforest:rainbow','Rainbow oak','彩虹橡树','Trees','Twilight Forest');
for(const [cls,b]of Object.entries(tfNames)) {
    let s=read(`${tfRoot}/${cls}.java`);
    let parent=s.match(/extends (TFBiome\w+)/)?.[1];
    const chain=[s];while(parent&&parent!=='TFBiomeBase') {const ps=read(`${tfRoot}/${parent}.java`);chain.unshift(ps);parent=ps.match(/extends (TFBiome\w+)/)?.[1];}
    const treeMethod=[...chain].reverse().map(x=>x.match(/public WorldGenAbstractTree func_150567_a\(Random \w+\)\s*\{([\s\S]*?)\n    \}/)?.[1]).find(Boolean)||tfBase.match(/public WorldGenAbstractTree func_150567_a\(Random \w+\)\s*\{([\s\S]*?)\n    \}/)[1];
    s=chain.join('\n');const add=e=>names(e,[b],Object.keys(eligible),['TWILIGHT_FOREST'],`TwilightForest/${cls}`);
    const disabled=/setTreesPerChunk\(-999\)/.test(s);
    if(!disabled) {
        for(const e of treeEntries(treeMethod))add(e);
        if(/taigaGen|megaPineGen|TFGenLargeWinter/.test(treeMethod))add(trees.spruce);
        if(/birchGen/.test(treeMethod))add(trees.birch);
        if(/field_76763_Q/.test(treeMethod))add(trees.oak);
    }
    if(!/canopyPerChunk = -999|canopyPerChunk = 0/.test(s)) {
        if(/DarkForest/.test(cls))add(dark);else if(cls==='TFBiomeOakSavanna')add(trees.oak);else add(canopy);
    }
    if(/mangrovesPerChunk = [1-9]/.test(s))add(mangrove);
    if(cls==='TFBiomeEnchantedForest')add(rainbow);
    // Vanilla BiomeDecorator defaults to two flowers; the final subclass override wins.
    const flowerCounts=[...s.matchAll(/(?:setFlowersPerChunk\(|field_76802_A\s*=\s*)(-?\d+)/g)];
    const flowersPerChunk=flowerCounts.length?+flowerCounts.at(-1)[1]:2;
    if(!/EnchantedForest|FireflyForest|OakSavanna/.test(cls)&&flowersPerChunk>0) {add(flowers[0]);add(flowers[9]);}
}
for(const i of [0,2,3,4,5,6,7,8,11,14,15])names(flowers[i],['Firefly Forest'],Object.keys(eligible),['TWILIGHT_FOREST'],'TFBiomeFireflyForest flower selector and double plants');
for(const i of [1,2,3])names(flowers[i],['Enchanted Forest'],Object.keys(eligible),['TWILIGHT_FOREST'],'TFBiomeEnchantedForest flower selector');
for(const i of [0,3,4,5,6,7,8,9])names(flowers[i],['Oak Savanna'],Object.keys(eligible),['TWILIGHT_FOREST'],'TFBiomeOakSavanna flower selector');
// Chinese names are supplied explicitly because the bundled BOP Chinese file uses obsolete keys.
const flowerZh=['苜蓿','沼泽花','死亡之花','荧光花','蓝绣球','橙色波斯菊','粉色水仙','野花','紫罗兰','白银莲','睡莲','末影莲','凤梨','眼球花','眼球花（上半部）','绒球蒲公英'];
const flower2Zh=['粉色木槿','铃兰','燃烧之花','薰衣草','一枝黄花','蓝铃花','矿工的喜悦','冰鸢尾','玫瑰'];
for(const [key,e]of Object.entries(bopFlowers)){const [b,i]=key.split(':');e.zhName=(b==='flowers'?flowerZh:flower2Zh)[+i];}
const treeZh={'Yellow Autumn':'黄色秋树',Bamboo:'竹树',Magic:'魔法树',Dark:'黑暗树',Dying:'垂死树',Fir:'杉树',Ethereal:'天域树','Orange Autumn':'橙色秋树',Origin:'起源树','Pink Cherry':'粉色樱花树',Maple:'枫树','White Cherry':'白色樱花树',Hellbark:'地狱皮树',Jacaranda:'蓝花楹','Sacred Oak':'神圣橡树',Mangrove:'红树',Palm:'棕榈树',Redwood:'红杉',Willow:'柳树',Pine:'松树',Mahogany:'桃花心木','Flowering Oak':'开花橡树',Apple:'苹果树',Persimmon:'柿子树'};
for(const e of Object.values(leafTrees))e.zhName=treeZh[e.name]||e.name;
entries.delete('bop:flowers:14');
for(const e of entries.values()) {
    e.zhName=e.zhName.replace(/树树/g,'树');
    if(!e.rules.length&&!e.note){e.note='No verified natural generation rule in the supported generator pools; not evidence of absence in other dimensions or structures.';e.zhNote='在支持的生成器群系池中未核实到自然生成规则；不代表其他维度或结构中不存在。';}
}
// Remove duplicate evidence, preserving separate generator eligibility.
for(const e of entries.values())e.rules=[...new Map(e.rules.map(r=>[JSON.stringify(r),r])).values()];
const output={version:'GTNH 2.9.0 / 2.9.1',generators:{RWG:'RWG-Alpha 1.5.3',RWG2_CONTINENTS:'RWG2 2.0.6 / 2.0.9'},eligible:Object.fromEntries(Object.entries(eligible).map(([g,n])=>[g,[...n].sort()])),entries:[...entries.values()]};
fs.mkdirSync('src/main/resources/amidst/gtnh',{recursive:true});
fs.writeFileSync('src/main/resources/amidst/gtnh/vegetation.json',JSON.stringify(output,null,2)+'\n');
// Keep the reviewable distribution appendix in sync with the shipped rules.
const dims={OVERWORLD:'主世界',NETHER:'下界',TWILIGHT_FOREST:'暮色森林',ROSS_128B:'罗斯 128b',END:'末地（未开放筛选）'};
const sections={Flowers:'花朵',Gardens:'潘马斯菜园','Fruit trees':'潘马斯果树',Trees:'树木'};
let md='# 植被分布规则表（GTNH 2.9.0／2.9.1）\n\n由 `tools/Generate-VegetationCatalog.mjs` 与应用目录同时生成。依据、标签含义和边界见[植被筛选说明](vegetation.md)。\n\n';
md+='名称规则仍须与当前生成器的可生成群系池取交集；标签规则使用 Worker 返回的运行时标签展开为具体群系。`!` 表示排除，`+` 表示同时满足，`/` 表示满足任一。表中空缺代表未核实，不代表绝对不生成。\n\n';
for(const [category,title]of Object.entries(sections)) {
    md+=`## ${title}\n\n| 植物 | 模组 | 维度／生成器与规则 |\n| --- | --- | --- |\n`;
    for(const e of output.entries.filter(e=>e.category===category)) {
        const grouped=new Map();
        for(const r of e.rules)for(const d of r.dimensions)for(const g of r.generators) {
            const key=d+' / '+g;
            const set=grouped.get(key)||new Set();
            if(r.names)for(const n of r.names)if(d!=='OVERWORLD'||eligible[g].has(n))set.add(n);
            if(r.tags)set.add(r.tags.map(c=>c.length?c.join(' + '):'不限群系标签').join(' / ')+(r.excludeNames?'；排除 '+r.excludeNames.join('、'):''));
            if(set.size)grouped.set(key,set);
        }
        const conditions=[...grouped].map(([k,v])=>{
            const [d,g]=k.split(' / ');return `${dims[d]} · ${g==='RWG'?'Alpha':'RWG2'}：${[...v].sort().join('、')}`;
        });
        if(e.zhNote)conditions.push(e.zhNote);
        md+=`| ${e.zhName} / ${e.name} | ${e.mod} | ${conditions.join('<br>')||'当前群系池无已核实匹配'} |\n`;
    }
    md+='\n';
}
fs.writeFileSync('docs/reference/vegetation-catalog.md',md);
console.log(`${output.entries.length} entries; ${output.entries.filter(e=>e.rules.length).length} with generation rules`);
