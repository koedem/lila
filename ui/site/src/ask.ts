import * as xhr from 'common/xhr';

lichess.load.then(() => {
  $('div.ask-container').each(function (this: HTMLElement) {
    rewire(this);
    const ask = this.firstElementChild as HTMLElement;
    $('.ask-ranked',ask).each(bindRankedAsk)
  });
});

function rewire(container: HTMLElement){
  const ask = container.firstElementChild as HTMLElement;
  $('.ask-xhr', ask).on('click', function (e: Event) {
    xhr.text((e.target as HTMLButtonElement).formAction, { method: 'post' }).then((frag: string) => {
      container!.innerHTML = frag;
      rewire(container);
    });
  });
  return false;
}

function bindRankedAsk (_: any, ask: Element): void {
  let dragging: Element|null = null;
  let origAfter: Element|null = null;
  let serverRanking = getRanking(ask);
  const askId = ask.getAttribute('id');
  
  $('.ask-ranked-choice', ask)
    .on('dragstart', (e: DragEvent) => {
      dragging = e.target as Element;
      origAfter = dragging.nextElementSibling;
      e.dataTransfer!.effectAllowed = 'move';
      e.dataTransfer!.setData('text/plain', '');
    })
    .on('dragenter',  (e: DragEvent) => {
      if (dragging == e.target) return;
      const target = e.target as Element;
      target.parentNode?.insertBefore(dragging!, isBefore(dragging!, target) ? target : target?.nextSibling);
    })
    .on('dragover', (e: DragEvent) => {
      e.preventDefault();
    })
    .on('dragend', () => {
      dragging?.parentNode?.insertBefore(dragging, origAfter);
    })
    .on('drop', () => {
      dragging = null;
      const ranking = getRanking(ask);
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