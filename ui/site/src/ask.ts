import * as xhr from 'common/xhr';

lichess.load.then(() => {
  $('div.ask-container').each(function (this: HTMLElement) {
    rewire(this);
    const ask = this.firstElementChild as HTMLElement;
    $('.ask-ranked', ask).each((_, el) => bindRankedAsk(el, ask.id));
    $('.ask-submit', ask).each((_, el) => bindSubmit(el, this));
  });
});

function rewire(container: Element): void {
  const ask = container.firstElementChild;
  if (!ask) return;
  $('.ask-xhr', ask).on('click', function (e: Event) {
    xhr.text((e.target as HTMLButtonElement).formAction, { method: 'post' }).then((frag: string) => {
      container!.innerHTML = frag;
      rewire(container);
    });
  });
}

function bindSubmit(button: Element, container: Element): void {
  const ask = container.firstElementChild;
  if (!ask) return;
  $(button).on('click', () => {

    const text = $('input.ask-text-field', ask).text();
    console.log(`text = ${text}`);
    xhr.text('/ask/feedback/' + ask.id, { 
      method: 'post',
      body: 'text=' + encodeURIComponent(text)
    }).then((frag: string) => {
      container!.innerHTML = frag;
      rewire(container);
    });
  });
}

function bindRankedAsk(container: Element, askId: string): void {
  let dragging: Element|null = null;
  let after: Element|null = null;
  let serverRanking = getRanking(container);
  $('.ask-ranked-choice', container)
    .on('dragstart', (e: DragEvent) => {
      dragging = e.target as Element;
      after = dragging.nextElementSibling;
      e.dataTransfer!.effectAllowed = 'move';
      e.dataTransfer!.setData('text/plain', '');
    })
    .on('dragenter',  (e: DragEvent) => {
      if (dragging == e.target) return;
      const target = e.target as Element;
      const before = isBefore(dragging!, target) ? target : target?.nextSibling;
      target.parentNode?.insertBefore(dragging!, before);
    })
    .on('dragover', (e: DragEvent) => {
      e.preventDefault();
    })
    .on('dragend', () => {
      dragging?.parentNode?.insertBefore(dragging, after);
    })
    .on('drop', () => {
      dragging = null;
      const ranking = getRanking(container);
      if (ranking != serverRanking) {
        xhr.text(`/ask/rank/${askId}/${ranking}`, { method: 'post' }).then(
          () => serverRanking = ranking,
          (rsp: string) => console.log(`XHR error: ${rsp}`));
      }
    });
}

function getRanking(ask: Element): string {
  return Array.from(
    $('.ask-ranked-choice', ask), 
    e => e?.getAttribute('value')
  ).join('-');
}

function isBefore(dragging: Element, target: Element|null) {
  if (dragging.parentElement == target?.parentElement)
    for (let it = dragging.previousSibling; it; it = it.previousSibling)
      if (it === target) return true;
  return false;
}