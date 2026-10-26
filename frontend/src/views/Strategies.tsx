import { Rec } from '../api';
import { Badge, Card, ErrorNote, KV, Skeleton, useApi } from '../ui';

export default function Strategies() {
  const { data, error } = useApi<Rec[]>('/api/strategies');
  return (
    <div className="stack">
      <ErrorNote error={error} />
      <div className="note info">Strategies produce <b>candidates only</b>. Claude reviews them and the independent risk engine decides; parameters are configuration (<code>app.strategies.&lt;NAME&gt;.&lt;key&gt;</code>) and cannot be changed by AI output. Short-option strategies are disabled.</div>
      {!data ? <Skeleton lines={4} /> : (
        <div className="grid three">
          {data.map((s) => (
            <Card key={s.name} title={s.name} right={<Badge tone="muted">v{s.version}</Badge>}>
              <h4>Effective parameters</h4>
              <KV rows={Object.entries({ ...s.default_parameters, ...s.configured_overrides }).map(([k, v]) => [k, String(v)] as [string, string])} />
              {Object.keys(s.configured_overrides ?? {}).length > 0 && <div className="small muted">Includes configured overrides.</div>}
            </Card>))}
        </div>)}
    </div>
  );
}
