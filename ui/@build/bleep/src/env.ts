import * as path from 'node:path';
import * as ps from 'node:process';

export interface BleepOpts {
  gulp?: boolean; // build css, default = true
  exclude?: string[]; // ignore modules (tutor)

  log?: {
    heap?: boolean; // show node rss in log statements, default = false
    time?: boolean; // show time in log statements, default = true
    ctx?: boolean; // show context (tsc, rollup, etc), default = true
    // omit color object to strip all color escapes
    color?: any; // default { bleep: 'cyan', rollup: 'blue', tsc: 'yellow', gulp: 'magenta' }
  };
}

export interface LichessModule {
  root: string; // absolute path to package.json parentdir (module root)
  name: string; // dirname of module root - usually the module import name
  moduleAlias?: string; // import name (if different from root name as with analysisBoard)
  pkg: any; // the entire package.json object
  build: { pre: string[][]; post: string[][] }; // pre & post build steps from package.json
  hasTsconfig?: boolean; // fileExistsSync('tsconfig.json')
  tscOptions?: string[]; // options from tsc/compile script in package json
  rollup?: LichessRollup[]; // targets from rollup.config.mjs
}

export interface LichessRollup {
  hostMod: LichessModule;
  input: string; // abs path to source
  output: string; // abs path to bundle destination
  importName?: string; // might as well be isAnalysisBoard boolean
  plugins?: any[]; // currently just to copy/replace stuff in site bundle
  onWarn?: (w: any, wf: any) => any; // to suppress 'this is undefined'
  isMain: boolean; // false for plugin bundles
}

export function init(root: string, opts?: BleepOpts) {
  env.rootDir = root;
  const laxOpts = opts as any;
  if (!laxOpts?.log?.color && laxOpts?.log?.colors)
    // fix common plural mistake
    laxOpts.log.color = laxOpts.log.colors;
  env.opts = laxOpts ? laxOpts : defaultOpts;
}

export const colorFuncs = {
  red: (text: string): string =>
    lines(text)
      .map(t => escape(t, codes.red))
      .join('\n'),
  green: (text: string): string =>
    lines(text)
      .map(t => escape(t, codes.green))
      .join('\n'),
  yellow: (text: string): string =>
    lines(text)
      .map(t => escape(t, codes.yellow))
      .join('\n'),
  blue: (text: string): string =>
    lines(text)
      .map(t => escape(t, codes.blue))
      .join('\n'),
  magenta: (text: string): string =>
    lines(text)
      .map(t => escape(t, codes.magenta))
      .join('\n'),
  cyan: (text: string): string =>
    lines(text)
      .map(t => escape(t, codes.cyan))
      .join('\n'),
  grey: (text: string): string =>
    lines(text)
      .map(t => escape(t, codes.grey))
      .join('\n'),
};

class Env {
  rootDir: string; // absolute path to lila project root
  opts: BleepOpts; // configure logging mostly
  moduleNames?: string[]; // list of modules to watch, undefined means all

  get uiDir(): string {
    return path.resolve(this.rootDir, 'ui');
  }
  get nodeDir(): string {
    return path.resolve(this.rootDir, 'node_modules');
  }
  get outDir(): string {
    return path.resolve(this.rootDir, 'public/compiled');
  }
  get bleepDir(): string {
    return path.resolve(this.rootDir, 'ui/@build/bleep');
  }
  get tsconfigDir(): string {
    return path.resolve(this.rootDir, 'ui/@build/bleep/.tsconfig');
  }

  log(d: any, { ctx = 'bleep', error = false } = {}) {
    let text: string =
      typeof d == 'string'
        ? d
        : d instanceof Buffer
        ? d.toString('utf8')
        : Array.isArray(d)
        ? d.join('\n')
        : JSON.stringify(d, undefined, 2);

    const show = this.opts.log;
    const esc = show?.color ? escape : (text: string, _: any) => text;
    const rss = Math.round(ps.memoryUsage.rss() / (1000 * 1000));

    if (!show?.color) {
      console.log('hey i"m trying');
      text = stripColorEscapes(text);
    }

    // strip the time displays from these contexts for consistent formatting
    if (ctx == 'gulp') text = text.replace(/\[\d\d:\d\d:\d\d] /, '');
    else if (ctx == 'tsc') text = text.replace(/\d?\d:\d\d:\d\d (PM|AM) /, '').replace('- ', '');

    const prefix = (
      (show?.time ? esc(prettyTime(), codes.grey) : '') +
      (show?.ctx && ctx ? `[${esc(ctx, colorForCtx(ctx, show?.color))}] ` : '') +
      (show?.heap ? `${esc(rss + '', rss > 5000 ? codes.red : codes.grey)} MB ` : '')
    ).trim();

    lines(text).forEach(line => console.log(`${prefix ? prefix + ' - ' : ''}${error ? esc(line, '31') : line}`));
  }
}

export const env = new Env();

export const codes: any = {
  black: '30',
  red: '31',
  green: '32',
  yellow: '33',
  blue: '34',
  magenta: '35',
  cyan: '36',
  grey: '90',
};

const defaultOpts: BleepOpts = {
  log: {
    heap: false,
    time: true,
    ctx: true,
    color: {
      bleep: codes.cyan,
      gulp: codes.magenta,
      tsc: codes.yellow,
      rollup: codes.blue,
    },
  },
  gulp: true,
  exclude: [],
};

const colorForCtx = (ctx: string, color: any): string =>
  color && ctx in color && color[ctx] in codes ? codes[color[ctx]] : codes.grey;

const escape = (text: string, code: string): string => `\x1b[${code}m${stripColorEscapes(text)}\x1b[0m`;

const lines = (s: string): string[] => s.split(/[\n\r\f]+/).filter(x => x);

const pad2 = (n: number) => (n < 10 ? `0${n}` : `${n}`);

function stripColorEscapes(text: string) {
  // eslint-disable-next-line no-control-regex
  return text.replace(/\x1b\[[0-9;]*m/, '');
}

function prettyTime() {
  const now = new Date();
  return `${pad2(now.getHours())}:${pad2(now.getMinutes())}:${pad2(now.getSeconds())} `;
}
