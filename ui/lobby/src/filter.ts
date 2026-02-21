import type { Hook } from './interfaces';
import {
  type FormLines,
  type FormObject,
  type FormStore,
  toFormLines,
  toFormObject,
  makeStore,
} from './form';
import type LobbyController from './ctrl';
import type { LichessStorage } from 'lib/storage';

interface FilterData {
  form: FormLines;
  filter: FormObject;
}

interface Filtered {
  visible: Hook[];
  hidden: number;
}

export default class Filter {
  store: FormStore;
  data: FilterData | null;
  open = false;
  uiCacheBuster: number = 1; // used to force re-init of filter UI

  constructor(
    storage: LichessStorage,
    readonly root: LobbyController,
  ) {
    this.store = makeStore(storage);
    this.set(this.store.get());
  }

  toggle = () => {
    this.open = !this.open;
  };

  set = (data: FormLines | null) => {
    this.data = data && {
      form: data,
      filter: toFormObject(data),
    };
  };

  save = (form: HTMLFormElement | null) => {
    const lines = form && toFormLines(form);
    if (lines) this.store.set(lines);
    else this.store.remove();
    this.set(lines);
    this.root.onSetFilter();
  };

  filter = (hooks: Hook[]): Filtered => {
    if (!this.data) return { visible: hooks, hidden: 0 };
    const f = this.data.filter,
      ratingRange = f.ratingRange && f.ratingRange.split('-').map((r: string) => parseInt(r, 10)),
      seen: string[] = [],
      visible: Hook[] = [];
    let hidden = 0;
    hooks.forEach(hook => {
      if (hook.action === 'cancel') visible.push(hook);
      else {
        if (
          !f.speed?.includes((hook.s || 1).toString() /* ultrabullet = bullet */) ||
          (ratingRange && (!hook.rating || hook.rating < ratingRange[0] || hook.rating > ratingRange[1]))
        ) {
          hidden++;
        } else {
          const hash = `${hook.t}${hook.rating ?? 0}`;
          if (!seen.includes(hash)) visible.push(hook);
          seen.push(hash);
        }
      }
    });
    return { visible, hidden };
  };
}
