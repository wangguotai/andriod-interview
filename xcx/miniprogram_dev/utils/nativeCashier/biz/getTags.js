const getTags = (tag) => {
  const app = getApp()
  const {
    appKey,
    from,
    refer,
  } = app.globalData;
  const _tags = ["ykminiapp"];
  appKey && _tags.push(appKey);
  refer && _tags.push(refer);
  from && _tags.push(from);
  tag && _tags.push(tag);
  const tags = _tags.filter(item => !!item).join(",");
  return tags;
}

export default getTags;